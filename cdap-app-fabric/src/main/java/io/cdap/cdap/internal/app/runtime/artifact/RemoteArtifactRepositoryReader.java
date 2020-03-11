package io.cdap.cdap.internal.app.runtime.artifact;

import com.google.common.base.Throwables;
import com.google.common.collect.Maps;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import io.cdap.cdap.api.artifact.ArtifactScope;
import io.cdap.cdap.api.artifact.ArtifactSummary;
import io.cdap.cdap.api.artifact.ArtifactVersion;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.cdap.api.plugin.PluginClass;
import io.cdap.cdap.api.plugin.PluginSelector;
import io.cdap.cdap.common.ArtifactNotFoundException;
import io.cdap.cdap.common.NotFoundException;
import io.cdap.cdap.common.conf.Constants;
import io.cdap.cdap.common.http.DefaultHttpRequestConfig;
import io.cdap.cdap.common.id.Id;
import io.cdap.cdap.common.internal.remote.RemoteClient;
import io.cdap.cdap.common.io.Locations;
import io.cdap.cdap.internal.app.runtime.plugin.PluginNotExistsException;
import io.cdap.cdap.internal.io.SchemaTypeAdapter;
import io.cdap.cdap.proto.artifact.PluginInfo;
import io.cdap.cdap.proto.id.ArtifactId;
import io.cdap.cdap.proto.id.NamespaceId;
import io.cdap.common.http.HttpMethod;
import io.cdap.common.http.HttpRequest;
import io.cdap.common.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.apache.twill.discovery.DiscoveryServiceClient;
import org.apache.twill.filesystem.Location;
import org.apache.twill.filesystem.LocationFactory;
import sun.net.www.protocol.http.HttpURLConnection;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public class RemoteArtifactRepositoryReader implements ArtifactRepositoryReader {
  public static final String LOCATION_FACTORY = "RemoteArtifactRepositoryReaderLocationFactory";

  private static final Gson GSON = new GsonBuilder()
    .registerTypeAdapter(Schema.class, new SchemaTypeAdapter())
    .create();
  private static final Type ARTIFACT_DETAIL_TYPE = new TypeToken<ArtifactDetail>() { }.getType();
  private static final Type PLUGIN_INFO_LIST_TYPE = new TypeToken<List<PluginInfo>>() { }.getType();

  private final RemoteClient remoteClient;
  private final LocationFactory locationFactory;

  @Inject
  public RemoteArtifactRepositoryReader(DiscoveryServiceClient discoveryClient,
                                        @Named(LOCATION_FACTORY)LocationFactory locationFactory) {
    this.remoteClient = new RemoteClient(
      discoveryClient, Constants.Service.APP_FABRIC_HTTP,
      new DefaultHttpRequestConfig(false), Constants.Gateway.INTERNAL_API_VERSION_3);
    this.locationFactory = locationFactory;
  }

  @Override
  public ArtifactDetail getArtifact(Id.Artifact artifactId) throws Exception {
    HttpResponse httpResponse;
    String url = "namespaces/" + artifactId.getNamespace().getId() + "/artifacts/" + artifactId.getName() + "/versions/" + artifactId.getVersion();
    HttpRequest.Builder requestBuilder = remoteClient.requestBuilder(HttpMethod.GET, url);
    httpResponse = execute(requestBuilder.build());
    ArtifactDetail detail = GSON.fromJson(httpResponse.getResponseBodyAsString(), ARTIFACT_DETAIL_TYPE);
    String path = detail.getDescriptor().getLocationURI().getPath();
    Location location = Locations.getLocationFromAbsolutePath(locationFactory, path);
    return new ArtifactDetail(new ArtifactDescriptor(detail.getDescriptor().getArtifactId(), location),
                              detail.getMeta());
  }

  @Override
  public Map.Entry<ArtifactDescriptor, PluginClass> findPlugin(
    NamespaceId namespace, Id.Artifact artifactId, String pluginType, String pluginName, PluginSelector selector)
    throws IOException, PluginNotExistsException, ArtifactNotFoundException {
    try {
        List<PluginInfo> infos = getPlugins(namespace, artifactId, pluginType, pluginName);
        if (infos.isEmpty()) {
          throw new PluginNotExistsException(namespace, pluginType, pluginName);
        }

        SortedMap<io.cdap.cdap.api.artifact.ArtifactId, PluginClass> plugins = new TreeMap<>();

        for (PluginInfo info : infos) {
          ArtifactSummary artifactSummary = info.getArtifact();
          io.cdap.cdap.api.artifact.ArtifactId pluginArtifactId = new io.cdap.cdap.api.artifact.ArtifactId(
            artifactSummary.getName(), new ArtifactVersion(artifactSummary.getVersion()), artifactSummary.getScope());
          PluginClass pluginClass = new PluginClass(info.getType(), info.getName(), info.getDescription(),
                                                    info.getClassName(), info.getConfigFieldName(),
                                                    info.getProperties());
          plugins.put(pluginArtifactId, pluginClass);
        }

        Map.Entry<io.cdap.cdap.api.artifact.ArtifactId, PluginClass> selected = selector.select(plugins);
        if (selected == null) {
          throw new PluginNotExistsException(namespace, pluginType, pluginName);
        }

        Location artifactLocation = getArtifactLocation(Artifacts.toArtifactId(namespace, selected.getKey()));
        return Maps.immutableEntry(new ArtifactDescriptor(selected.getKey(), artifactLocation), selected.getValue());
    } catch (PluginNotExistsException e) {
      throw e;
    } catch (ArtifactNotFoundException e) {
      throw new PluginNotExistsException(namespace, pluginType, pluginName);
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  /**
   * Retrieves the {@link Location} of a given artifact.
   */
  private Location getArtifactLocation(ArtifactId artifactId) throws IOException, ArtifactNotFoundException {
    HttpRequest.Builder requestBuilder =
      remoteClient.requestBuilder(
        HttpMethod.GET, String.format("namespaces/%s/artifact-internals/artifacts/%s/versions/%s/location",
                                      artifactId.getNamespace(), artifactId.getArtifact(), artifactId.getVersion()));
    HttpResponse response = remoteClient.execute(requestBuilder.build());

    if (response.getResponseCode() == HttpResponseStatus.NOT_FOUND.code()) {
      throw new ArtifactNotFoundException(artifactId);
    }
    if (response.getResponseCode() != 200) {
      throw new IOException("Exception while getting artifacts list: " + response.getResponseCode()
                              + ": " + response.getResponseBodyAsString());
    }

    String path = response.getResponseBodyAsString();
    Location location = Locations.getLocationFromAbsolutePath(locationFactory, path);
    if (!location.exists()) {
      throw new IOException(String.format("Artifact Location does not exist %s for artifact %s version %s",
                                          path, artifactId.getArtifact(), artifactId.getVersion()));
    }
    return location;
  }

 /**
   * Gets a list of {@link PluginInfo} from the artifact extension endpoint.
   *
   * @param namespaceId namespace of the call happening in
   * @param parentArtifactId the parent artifact id
   * @param pluginType the plugin type to look for
   * @param pluginName the plugin name to look for
   * @return a list of {@link PluginInfo}
   * @throws IOException if it failed to get the information
   * @throws PluginNotExistsException if the given plugin type and name doesn't exist
   */
  private List<PluginInfo> getPlugins(NamespaceId namespaceId,
                                      Id.Artifact parentArtifactId,
                                      String pluginType,
                                      String pluginName) throws IOException, PluginNotExistsException {
    HttpRequest.Builder requestBuilder =
      remoteClient.requestBuilder(
        HttpMethod.GET,
        String.format("namespaces/%s/artifacts/%s/versions/%s/extensions/%s/plugins/%s?scope=%s&pluginScope=%s",
                      namespaceId.getNamespace(), parentArtifactId.getName(),
                      parentArtifactId.getVersion(), pluginType, pluginName,
                      NamespaceId.SYSTEM.equals(parentArtifactId.getNamespace())
                        ? ArtifactScope.SYSTEM : ArtifactScope.USER,
                      NamespaceId.SYSTEM.equals(namespaceId.getNamespaceId())
                        ? ArtifactScope.SYSTEM : ArtifactScope.USER
                      ));
    HttpResponse response = remoteClient.execute(requestBuilder.build());

    if (response.getResponseCode() == HttpResponseStatus.NOT_FOUND.code()) {
      throw new PluginNotExistsException(namespaceId, pluginType, pluginName);
    }

    if (response.getResponseCode() != 200) {
      throw new IllegalArgumentException("Failure in getting plugin information with type " + pluginType + " and name "
                                           + pluginName + " that extends " + parentArtifactId
                                           + ". Reason is " + response.getResponseCode() + ": "
                                           + response.getResponseBodyAsString());
    }

    return GSON.fromJson(response.getResponseBodyAsString(), PLUGIN_INFO_LIST_TYPE);
  }


  private HttpResponse execute(HttpRequest request) throws IOException, NotFoundException {
    HttpResponse httpResponse = remoteClient.execute(request);
    if (httpResponse.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND) {
      throw new NotFoundException(httpResponse.getResponseBodyAsString());
    }
    if (httpResponse.getResponseCode() != HttpURLConnection.HTTP_OK) {
      throw new IOException(httpResponse.getResponseBodyAsString());
    }
    return httpResponse;
  }

}
