/*
* Copyright © 2017 Cask Data, Inc.
*
* Licensed under the Apache License, Version 2.0 (the "License"); you may not
* use this file except in compliance with the License. You may obtain a copy of
* the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
* WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
* License for the specific language governing permissions and limitations under
* the License.
*/

import React, {Component} from 'react';
import SystemServicesStore from 'services/SystemServicesStore';
import isEqual from 'lodash/isEqual';
import SortableStickyTable from 'components/SortableStickyTable';
import T from 'i18n-react';
import IconSVG from 'components/IconSVG';
import classnames from 'classnames';
import Datasource from 'services/datasource';
import LoadingSVG from 'components/LoadingSVG';
import {MyServiceProviderApi} from 'api/serviceproviders';
import TextboxOnValium from 'components/TextboxOnValium';
import Alert from 'components/Alert';

require('./ServicesTable.scss');

const ADMINPREFIX = 'features.Administration.Services';
const DEFAULTSERVICES = [
  'appfabric',
  'dataset.executor',
  'explore.service',
  'log.saver',
  'messaging.service',
  'metadata.service',
  'metrics',
  'metrics.processor',
  'streams',
  'transaction'
];
const tableHeaders = [
  {
    label: T.translate(`${ADMINPREFIX}.headers.status`),
    property: 'status'
  },
  {
    label: T.translate(`${ADMINPREFIX}.headers.name`),
    property: 'name',
    defaultSortby: true
  },
  {
    label: T.translate(`${ADMINPREFIX}.headers.provisioned`),
    property: 'provisioned'
  },
  {
    label: T.translate(`${ADMINPREFIX}.headers.requested`),
    property: 'requested'
  },
  {
    label: '',
    property: ''
  }
];
export default class ServicesTable extends Component {
  state = {
    services: SystemServicesStore.getState().services,
    showAlert: false,
    alertType: null,
    alertMessage: null
  };

  servicePolls = [];

  resetEditInstances = () => {
    let services = [...this.state.services];
    services = services.map(service => {
      service.editInstance = false;
      return service;
    });
    this.setState({
      services
    });
  };

  editRequestedServiceInstance = (serviceName, index) => {
    if (this.state.services[index].editInstance) {
      return;
    }
    let services = [...this.state.services];
    services = services.map(service => {
      if (serviceName === service.name) {
        return Object.assign({}, service, {
          editInstance: true
        });
      }
      return service;
    });
    this.setState({
      services
    });
  };

  resetAlert = () => {
    this.setState({
      showAlert: false,
      alertType: null,
      alertMessage: null
    });
  };

  serviceInstanceRequested = (serviceid, value) => {
    console.log(serviceid, value);
    MyServiceProviderApi
      .setProvisions({serviceid}, {instances : value})
      .subscribe(
        () => {},
        (err) => {
          this.resetEditInstances();
          this.setState({
            showAlert: true,
            alertType: 'error',
            alertMessage: err.response
          });
        }
      );
  };

  fetchServiceStatus = (serviceid) => {
    if (Object.keys(this.state.services).length) {
      return;
    }
    this.servicePolls.push(
      MyServiceProviderApi
        .pollServiceStatus({serviceid})
        .subscribe(
          (res) => {
            let services = {...this.state.services};
            services[serviceid] = {
              status: res.status
            };
            this.setState({services});
          }
        )
    );
  };

  // This is when backend does not return for /system/services call
  // Make calls to individual services to get their status
  fetchStatusFromIndividualServices = () => {
    DEFAULTSERVICES.forEach(service => this.fetchServiceStatus(service));
  }

  componentDidMount() {
    let serviceStatusTimeout = setTimeout(this.fetchStatusFromIndividualServices, 10000);
    this.systemServicesSubscription = SystemServicesStore.subscribe(() => {
      let {services} = SystemServicesStore.getState();
      if (!isEqual(services, this.state.services)) {
        this.setState({
          services
        });
        clearTimeout(serviceStatusTimeout);
        this.servicePolls.forEach(poll => poll.dispose());
      }
    });
  }

  componentWillUnmount() {
    if (this.systemServicesSubscription) {
      this.systemServicesSubscription();
    }
  }

  renderTableBody = (services) => {
    return (
      <table className="table-sm">
        <tbody>
          {
            services.map((service, i) => {
               let logUrl = Datasource.constructUrl({
                _cdapPath : `/system/services/${service.name}/logs`
              });

              logUrl = `/downloadLogs?type=raw&backendUrl=${encodeURIComponent(logUrl)}`;

              return (
                <tr key={service.name}>
                  <td>
                    <span className="status-circle">
                      <IconSVG
                        name="icon-circle"
                        className={classnames({
                          "text-success": service.status === 'OK',
                          "text-danger": service.status === 'NOTOK'
                        })}
                      />
                    </span>
                  </td>
                  <td>
                    <span>{T.translate(`${ADMINPREFIX}.${service.name.replace(/\./g, '_')}`)}</span>
                  </td>
                  <td>
                    <span>{service.provisioned || '--'}</span>
                  </td>
                  <td>
                    <span
                      onClick={this.editRequestedServiceInstance.bind(this, service.name, i)}
                      className="request-instances"
                    >
                      {
                        service.editInstance ?
                          <TextboxOnValium
                            className="<form-control></form-control>"
                            value={service.requested}
                            onBlur={this.resetEditInstances}
                            onChange={this.serviceInstanceRequested.bind(this, service.name)}
                          />
                        :
                          <span className="requested-instances-holder">{service.requested || '--'}</span>
                      }
                    </span>
                  </td>
                  <td>
                    <a href={logUrl} target="_blank">{T.translate(`${ADMINPREFIX}.viewlogs`)}</a>
                  </td>
                </tr>
              );
            })
          }
        </tbody>
      </table>
    );
  };

  render() {
    if (!Object.keys(this.state.services).length) {
      return (
        <div className="services-table">
          <LoadingSVG />
        </div>
      );
    }
    return (
      <div className="services-table">
        <SortableStickyTable
          className="table-sm"
          entities={this.state.services}
          tableHeaders={tableHeaders}
          renderTableBody={this.renderTableBody}
        />
        <Alert
          showAlert={this.state.showAlert}
          type={this.state.alertType}
          message={this.state.alertMessage}
          onClose={this.resetAlert}
        />
      </div>
    );
  }
}

