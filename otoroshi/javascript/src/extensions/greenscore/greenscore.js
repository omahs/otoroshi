import React, { Component, useEffect, useState } from 'react';
import { nextClient } from '../../services/BackOfficeServices';
import {
  NgBooleanRenderer, NgNumberRenderer, NgSelectRenderer, NgTextRenderer
} from '../../components/nginputs'
import { Table } from '../../components/inputs/Table';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { v4 as uuid } from 'uuid';
import { FeedbackButton } from '../../pages/RouteDesigner/FeedbackButton';
import { isUndefined } from 'lodash';

export const MAX_GREEN_SCORE_NOTE = 6000;
const GREEN_SCORE_GRADES = {
  "#2ecc71": rank => rank >= MAX_GREEN_SCORE_NOTE,
  "#27ae60": rank => rank < MAX_GREEN_SCORE_NOTE && rank >= 3000,
  "#f1c40f": rank => rank < 3000 && rank >= 2000,
  "#d35400": rank => rank < 2000 && rank >= 1000,
  "#c0392b": rank => rank < 1000
}

export function calculateGreenScore(routeRules) {
  const { sections } = routeRules;

  const score = sections.reduce((acc, item) => {
    return acc + item.rules.reduce((acc, rule) => {
      return acc += (rule.enabled ? MAX_GREEN_SCORE_NOTE * (rule.section_weight / 100) * (rule.weight / 100) : 0)
    }, 0)
  }, 0);

  const rankIdx = Object.entries(GREEN_SCORE_GRADES).findIndex(grade => grade[1](score))

  return {
    score,
    rank: rankIdx === -1 ? "Not evaluated" : Object.keys(GREEN_SCORE_GRADES)[rankIdx],
    letter: String.fromCharCode(65 + rankIdx)
  }
}

export function getRankAndLetterFromScore(score) {
  const rankIdx = Object.entries(GREEN_SCORE_GRADES).findIndex(grade => grade[1](score))

  return {
    score,
    rank: rankIdx === -1 ? "Not evaluated" : Object.keys(GREEN_SCORE_GRADES)[rankIdx],
    letter: String.fromCharCode(65 + rankIdx)
  }
}

function GreenScoreForm(props) {
  const { sections, dynamic_sections } = props.rulesConfig;

  console.log(props)

  const onChange = (checked, currentSectionIdx, currentRuleIdx) => {
    props.onChange({
      rulesConfig: {
        ...props.rulesConfig,
        sections: sections.map((section, sectionIdx) => {
          if (currentSectionIdx !== sectionIdx)
            return section

          return {
            ...section,
            rules: section.rules.map((rule, ruleIdx) => {
              if (ruleIdx !== currentRuleIdx)
                return rule;

              return {
                ...rule,
                enabled: checked
              }
            })
          }
        })
      }
    })
  }

  const handleDynamicSections = (key, value) => {
    props.onChange({
      rulesConfig: {
        ...props.rulesConfig,
        dynamic_sections: {
          ...dynamic_sections,
          [key]: value
        }
      }
    })
  }

  return <div>
    <div className='p-3'>
      <h4>Dynamic configuration</h4>
      <p>These threshold are used to rank the service. The four checks are applied on the received and sended requests.</p>
      {Object.entries(dynamic_sections)
        .map(([key, value]) => {
          return <div className='row' id={key}>
            <NgNumberRenderer
              value={value}
              label={key.match(/[A-Z][a-z]+/g).join(" ")}
              schema={{}}
              onChange={e => {
                handleDynamicSections(key, e)
              }} />
          </div>
        })}
    </div>

    {sections.map(({ id, rules }, currentSectionIdx) => {
      return <div key={id} className='p-3'>
        <h4 className='mb-3' style={{ textTransform: 'capitalize' }}>{id}</h4>
        {rules.map(({ id, description, enabled, advice }, currentRuleIdx) => {
          return <div key={id}
            className='d-flex align-items-center'
            style={{
              cursor: 'pointer'
            }}
            onClick={e => {
              e.stopPropagation();
              onChange(!enabled, currentSectionIdx, currentRuleIdx)
            }}>
            <div style={{ flex: 1 }}>
              <p className='offset-1 mb-0' style={{ fontWeight: 'bold' }}>{description}</p>
              <p className='offset-1'>{advice}</p>
            </div>
            <div style={{ minWidth: 52 }}>
              <NgBooleanRenderer
                value={enabled}
                onChange={checked => onChange(checked, currentSectionIdx, currentRuleIdx)}
                schema={{}}
                ngOptions={{
                  spread: true
                }}
              />
            </div>
          </div>
        })}
      </div>
    })}
  </div>
}

class GreenScoreRoutesForm extends React.Component {

  state = {
    selectedRoute: undefined,
    editRoute: undefined,
  }

  addRoute = () => {
    const routeId = this.state.selectedRoute;

    this.props.rootOnChange({
      ...this.props.rootValue,
      routes: [
        ...this.props.rootValue.routes,
        {
          routeId,
          rulesConfig: this.props.rulesTemplate
        }
      ]
    });

    this.editRoute(routeId);
  }

  editRoute = routeId => this.setState({
    editRoute: routeId,
    selectedRoute: undefined
  })

  onWizardClose = () => {
    this.setState({
      editRoute: undefined
    })
  }

  onRulesChange = rulesConfig => {
    this.props.rootOnChange({
      ...this.props.rootValue,
      routes: this.props.rootValue.routes.map(route => {
        if (route.routeId === this.state.editRoute) {
          return {
            ...route,
            ...rulesConfig
          }
        }
        return route
      })
    })
  }

  render() {
    const { routeEntities } = this.props;
    const { routes } = this.props.rootValue;

    const { selectedRoute, editRoute } = this.state;

    return <div>
      {editRoute && <div className="wizard">
        <div className="wizard-container">
          <div className="d-flex" style={{ flexDirection: 'column', padding: '2.5rem', flex: 1 }}>
            <label style={{ fontSize: '1.15rem' }}>
              <i className="fas fa-times me-3" onClick={this.onWizardClose} style={{ cursor: 'pointer' }} />
              <span>Check the rules of the route</span>
            </label>
            <GreenScoreForm
              rulesConfig={routes.find(r => r.routeId === editRoute).rulesConfig}
              onChange={this.onRulesChange}
            />
            <div className="d-flex mt-auto ms-auto justify-content-between align-items-center">
              <FeedbackButton
                style={{
                  backgroundColor: 'var(--color-primary)',
                  borderColor: 'var(--color-primary)',
                  padding: '12px 48px',
                }}
                onPress={() => Promise.resolve()}
                onSuccess={this.onWizardClose}
                icon={() => <i className="fas fa-paper-plane" />}
                text="Save the rules"
              />
            </div>
          </div>
        </div>
      </div>}

      <div className='row my-2'>
        <label className='col-xs-12 col-sm-2 col-form-label'>Add to this group</label>
        <div className='d-flex align-items-center col-sm-10'>
          <div style={{ flex: 1 }}>
            <NgSelectRenderer
              value={selectedRoute}
              placeholder="Select a route"
              label={' '}
              ngOptions={{
                spread: true
              }}
              onChange={selectedRoute => this.setState({ selectedRoute })}
              margin={0}
              style={{ flex: 1 }}
              options={routeEntities.filter(route => !routes.find(r => route.id === r.routeId))}
              optionsTransformer={(arr) =>
                arr.map((item) => ({ label: item.name, value: item.id }))
              }
            />
          </div>
          <button
            type='button'
            className='btn btn-primaryColor mx-2'
            disabled={!selectedRoute}
            onClick={this.addRoute}>
            Start to configure
          </button>
        </div>
      </div>

      <div style={{
        background: 'var(--bg-color_level2)',
        padding: 12
      }}>
        <div className='d-flex align-items-center m-3' style={{ fontWeight: 'bold' }}>
          <div style={{ flex: 1 }}>
            <label>Route name</label>
          </div>
          <span>Action</span>
        </div>
        {routes.length === 0 && <p className='text-center' style={{ fontWeight: 'bold' }}>No routes added</p>}
        {routes.map(({ routeId, rulesConfig }) => {
          const rankInformations = calculateGreenScore(rulesConfig)
          return <div key={routeId} className='d-flex align-items-center mx-3 my-1'>
            <div style={{ flex: 1 }}>
              <label>{routeEntities.find(r => r.id === routeId)?.name}</label>

              <span><i className="fa fa-leaf ms-2" style={{ color: rankInformations.rank }} /></span>
            </div>
            <button type="button" className='btn btn-primary' onClick={() => this.editRoute(routeId)}>
              <i className='fa fa-cog' />
            </button>
          </div>
        })}
      </div>
    </div>
  }
}

function GreenScoreColumm(props) {
  const score = props
    .routes
    .reduce((acc, route) => calculateGreenScore(route.rulesConfig).score + acc, 0) / props.routes.length;

  const { letter, rank } = getRankAndLetterFromScore(score)

  return <div className='text-center'>
    {letter} <i className="fa fa-leaf" style={{ color: rank }} />
  </div>
}

function DynamicScoreColumn(props) {
  const [score, setScore] = useState(undefined)
  const greenScoreId = props.value.id;

  useEffect(() => {
    fetch(`/bo/api/proxy/api/extensions/green-score/${greenScoreId}/eco`, {
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
    })
      .then(r => r.json())
      .then(setScore)
  }, [])

  return <div className='text-center'>{score}</div>
}

class GreenScoreConfigsPage extends Component {

  state = {
    routes: [],
    rulesTemplate: isUndefined
  }

  formSchema = {
    _loc: {
      type: 'location'
    },
    id: {
      type: 'string',
      disabled: true,
      label: 'Id',
      props: {
        placeholder: '---'
      }
    },
    name: {
      type: 'string',
      label: 'Name',
      props: {
        placeholder: 'My Awesome Green Score'
      },
    },
    description: {
      type: 'string',
      label: 'Description',
      props: {
        placeholder: 'Description of the Green Score config'
      },
    },
    metadata: {
      type: 'object',
      label: 'Metadata'
    },
    tags: {
      type: 'array',
      label: 'Tags'
    },
    routes: {
      renderer: props => <GreenScoreRoutesForm {...props} routeEntities={this.state.routes} rulesTemplate={this.state.rulesTemplate} />
    }
  };

  columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
    },
    {
      title: 'Description',
      filterId: 'description',
      content: (item) => item.description
    },
    {
      title: 'Green score group',
      notFilterable: true,
      content: GreenScoreColumm
    },
    {
      title: 'Dynamic score',
      notFilterable: true,
      Cell: DynamicScoreColumn
    }
  ];

  formFlow = [
    '_loc',
    {
      type: 'group',
      name: 'Informations',
      collapsed: false,
      fields: [
        'id',
        'name',
        'description'
      ]
    },
    {
      type: 'group',
      name: 'Routes',
      collapsed: false,
      fields: ['routes']
    },
    {
      type: 'group',
      name: 'Misc.',
      collapsed: true,
      fields: ['tags', 'metadata'],
    }
  ];

  componentDidMount() {
    this.props.setTitle(`All Green Score configs.`);

    nextClient.forEntity(nextClient.ENTITIES.ROUTES)
      .findAll()
      .then(routes => {
        this.setState({ routes })
      })

    fetch("/bo/api/proxy/api/extensions/green-score/template", {
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
    })
      .then(r => r.json())
      .then(rulesTemplate => this.setState({ rulesTemplate }))
  }

  render() {
    const client = BackOfficeServices.apisClient('green-score.extensions.otoroshi.io', 'v1', 'green-scores');

    return (
      <Table
        parentProps={this.props}
        selfUrl="extensions/green-score/green-score-configs"
        defaultTitle="All Green Score configs."
        defaultValue={() => ({
          id: 'green-score-config_' + uuid(),
          name: 'My Green Score',
          description: 'An awesome Green Score',
          tags: [],
          metadata: {},
          routes: [],
          config: {

          }
        })}
        itemName="Green Score config"
        formSchema={this.formSchema}
        formFlow={this.formFlow}
        columns={this.columns}
        stayAfterSave={true}
        fetchItems={(paginationState) => client.findAll()}
        updateItem={client.update}
        deleteItem={client.delete}
        createItem={client.create}
        navigateTo={(item) => {
          window.location = `/bo/dashboard/extensions/green-score/green-score-configs/edit/${item.id}`
        }}
        itemUrl={(item) => `/bo/dashboard/extensions/green-score/green-score-configs/edit/${item.id}`}
        showActions={true}
        showLink={true}
        rowNavigation={true}
        extractKey={(item) => item.id}
        export={true}
        kubernetesKind={"GreenScore"}
      />
    );
  }
}

const GreenScoreExtensionId = "otoroshi.extensions.GreenScore"
const GreenScoreExtension = (ctx) => {
  return {
    id: GreenScoreExtensionId,
    sidebarItems: [
      {
        title: 'Green scores',
        text: 'All your Green Scores',
        path: 'extensions/green-score/green-score-configs',
        icon: 'leaf'
      }
    ],
    creationItems: [],
    dangerZoneParts: [],
    features: [
      {
        title: 'Green Score configs.',
        description: 'All your Green Score configs.',
        img: 'leaf', // TODO: change image
        link: '/extensions/green-score/green-score-configs',
        display: () => true,
        icon: () => 'fa-leaf', // TODO: change icon
      },
    ],
    searchItems: [
      {
        action: () => {
          window.location.href = `/bo/dashboard/green-score/green-score-configs`
        },
        env: <span className="fas fa-leaf" />,
        label: 'Green Score configs.',
        value: 'green-score-configs',
      }
    ],
    routes: [
      // TODO: add more route here if needed
      {
        path: '/extensions/green-score/green-score-configs/:taction/:titem',
        component: (props) => {
          return <GreenScoreConfigsPage {...props} />
        }
      },
      {
        path: '/extensions/green-score/green-score-configs/:taction',
        component: (props) => {
          return <GreenScoreConfigsPage {...props} />
        }
      },
      {
        path: '/extensions/green-score/green-score-configs',
        component: (props) => {
          return <GreenScoreConfigsPage {...props} />
        }
      }
    ],
  }
}

export function setupGreenScoreExtension(registerExtensionThunk) {
  registerExtensionThunk(GreenScoreExtensionId, GreenScoreExtension);
}