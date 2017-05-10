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
import {UncontrolledDropdown} from 'components/UncontrolledComponents';
import {DropdownToggle, DropdownMenu, DropdownItem} from 'reactstrap';
import IconSVG from 'components/IconSVG';
import DataPrepStore from 'components/DataPrep/store';
import {objectQuery} from 'services/helpers';
import T from 'i18n-react';
import classnames from 'classnames';
import MyDataPrepApi from 'api/dataprep';
import NamespaceStore from 'services/NamespaceStore';
import DataPrepActions from 'components/DataPrep/store/DataPrepActions';
import {setWorkspace} from 'components/DataPrep/store/DataPrepActionCreator';
require('./SamplerDropdown.scss');
const samplerMethods = {
  first: 'first',
  bernoulli: 'bernoulli',
  poisson: 'poisson',
  reservoir: 'reservoir'
};
const PREFIX = `features.DataPrep.SamplerDropdown`;
const NUMLINES = 10000;
const DEFAULT_FRACTION = 0.35;
const CONTENT_TYPE = 'text/plain';

export default class SamplerDropdown extends Component {
  constructor(props) {
    super(props);
    let samplerMethod = objectQuery(DataPrepStore.getState(), 'dataprep', 'workspaceInfo', 'properties', 'sampler');
    this.dropdownOptions = Object.keys(samplerMethods).map(method => ({
      name: method,
      label: T.translate(`${PREFIX}.${method}.label`)
    }));
    let defaultValue = this.dropdownOptions.filter(option => option.name === samplerMethod);
    this.state = {
      samplerMethod: defaultValue.length ? defaultValue[0] : this.dropdownOptions[0]
    };
    this.onSamplerChange = this.onSamplerChange.bind(this);
  }
  componentDidMount() {
    this.storeSubscription = DataPrepStore.subscribe(() => {
      let samplerMethod = objectQuery(DataPrepStore.getState(), 'dataprep', 'workspaceInfo', 'properties', 'sampler');
      let match = this.dropdownOptions.filter(method => method.name === samplerMethod);
      if (match.length && match[0].name !== this.state.samplerMethod.name) {
        this.setState({
          samplerMethod: match[0]
        });
      }
    });
  }
  onSamplerChange(samplerMethod) {
    let {selectedNamespace: namespace} = NamespaceStore.getState();
    let path = objectQuery(DataPrepStore.getState(), 'dataprep', 'workspaceInfo', 'properties', 'path');
    let workspaceId = objectQuery(DataPrepStore.getState(), 'dataprep', 'workspaceId');

    let params = {
      namespace,
      path,
      lines: NUMLINES,
      fraction: DEFAULT_FRACTION,
      sampler: samplerMethod.name
    };
    const headers = {
      'Content-Type': CONTENT_TYPE
    };
    MyDataPrepApi
      .readFile(params, null, headers)
      .flatMap(() => {
        return setWorkspace(workspaceId);
      })
      .subscribe(
        () => {},
        (err) => {
          DataPrepStore.dispatch({
            type: DataPrepActions.setError,
            payload: {
              message: err.message || err.response.message
            }
          });
        }
      );
  }
  render() {
    return (
      <div className="dataprep-sampler-dropdown">
        <IconSVG
          name="icon-refresh"
          onClick={this.onSamplerChange.bind(this, this.state.samplerMethod)}
        />
        <UncontrolledDropdown
          className="collapsed-dropdown-toggle"
        >
          <DropdownToggle caret>
            {T.translate(`${PREFIX}.title`, {sampleMethod: this.state.samplerMethod.label})}
          </DropdownToggle>
          <DropdownMenu>
            {
              this.dropdownOptions.map(option => {
                return (
                  <DropdownItem
                    className={classnames({
                      'selected': this.state.samplerMethod.name === option.name
                    })}
                    onClick={this.onSamplerChange.bind(this, option)}
                  >
                    {
                      this.state.samplerMethod.name === option.name ?
                        <span>
                          <IconSVG name="icon-check"/>
                        </span>
                      :
                        null
                    }
                    <span>{option.label}</span>
                  </DropdownItem>
                );
              })
            }
          </DropdownMenu>
        </UncontrolledDropdown>
      </div>
    );
  }
}
