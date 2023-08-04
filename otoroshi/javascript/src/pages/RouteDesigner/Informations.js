import React, { forwardRef, useEffect, useImperativeHandle, useState } from 'react';
import { LabelAndInput, NgBoxBooleanRenderer, NgForm } from '../../components/nginputs';
import { nextClient } from '../../services/BackOfficeServices';
import { useHistory, useLocation } from 'react-router-dom';
import { calculateGreenScore, useEntityFromURI } from '../../util';
import { FeedbackButton } from './FeedbackButton';
import { RouteForm } from './form';
import { Button } from '../../components/Button';
import { ENTITIES, FormSelector } from '../../components/FormSelector';
import GreenScoreForm from './GreenScoreForm';

export const Informations = forwardRef(
  ({ isCreation, value, setValue, setSaveButton, routeId, query }, ref) => {
    const history = useHistory();
    const location = useLocation();
    const [showAdvancedForm, toggleAdvancedForm] = useState(false);

    const { capitalize, lowercase, fetchName, link } = useEntityFromURI();

    const isOnRouteCompositions = location.pathname.includes('route-compositions');
    const entityName = isOnRouteCompositions ? 'route composition' : 'route';

    useImperativeHandle(ref, () => ({
      onTestingButtonClick() {
        history.push(`/${link}/${value.id}?tab=flow`, { showTryIt: true });
      },
    }));

    useEffect(() => {
      setSaveButton(
        <FeedbackButton
          className="ms-2 mb-1"
          onPress={saveRoute}
          text={isCreation ? `Create ${entityName}` : `Save ${entityName}`}
          icon={() => <i className="fas fa-paper-plane" />}
        />
      );
    }, [value]);

    function saveRoute() {
      if (isCreation || location.state?.routeFromService) {
        return nextClient
          .create(nextClient.ENTITIES[fetchName], value)
          .then(() => history.push(`/${link}/${value.id}?tab=flow`));
      } else {
        return nextClient.update(nextClient.ENTITIES[fetchName], value).then((res) => {
          if (!res.error) setValue(res);
        });
      }
    }

    const schema = {
      id: {
        type: 'string',
        visible: false,
      },
      name: {
        type: 'string',
        label: `${capitalize} name`,
        placeholder: `Your ${lowercase} name`,
        help: `The name of your ${lowercase}. Only for debug and human readability purposes.`,
        // constraints: [constraints.required()],
      },
      enabled: {
        type: 'bool',
        label: 'Enabled',
        props: {},
      },
      capture: {
        type: 'bool',
        label: 'Capture route traffic',
        props: {
          labelColumn: 3,
        },
      },
      debug_flow: {
        type: 'bool',
        label: 'Debug the route',
        props: {
          labelColumn: 3,
        },
      },
      export_reporting: {
        type: 'bool',
        label: 'Export reporting',
        props: {
          labelColumn: 3,
        },
      },
      description: {
        type: 'string',
        label: 'Description',
        placeholder: 'Your route description',
        help: 'The description of your route. Only for debug and human readability purposes.',
      },
      groups: {
        type: 'array-select',
        label: 'Groups',
        props: {
          optionsFrom: '/bo/api/proxy/api/groups',
          optionsTransformer: (arr) => arr.map((item) => ({ value: item.id, label: item.name })),
        },
      },
      core_metadata: {
        label: 'Metadata shortcuts',
        type: 'string',
        customRenderer: (props) => {
          const metadata = props.rootValue?.metadata || {};

          const CORE_BOOL_METADATA = [
            {
              key: 'otoroshi-core-user-facing',
              label: 'User Facing',
              description:
                'The fact that this service will be seen by users and cannot be impacted by the Snow Monkey',
            },
            {
              key: 'otoroshi-core-use-akka-http-client',
              label: 'Use Akka Http Client',
              description: 'Use akka http client for this service',
            },
            {
              key: 'otoroshi-core-use-netty-http-client',
              label: 'Use Netty Client',
              description: 'Use netty http client for this service',
            },
            {
              key: 'otoroshi-core-use-akka-http-ws-client',
              label: 'Use Akka Http Ws Client',
              description: 'Use akka http client for this service on websocket calls',
            },
            {
              key: 'otoroshi-core-issue-lets-encrypt-certificate',
              label: 'Issue a Lets Encrypt Certificate',
              description: 'Flag to automatically issue a lets encrypt cert for this service',
            },
            {
              key: 'otoroshi-core-issue-certificate',
              label: 'Issue a Certificate',
              description: 'Flag to automatically issue a cert for this service',
            },
          ];

          const CORE_STRING_METADATA = [
            {
              key: 'otoroshi-core-issue-certificate-ca',
              label: 'Issue Certificate CA',
              description: 'CA for cert issuance',
            },
            {
              key: 'otoroshi-core-openapi-url',
              label: 'OPENAPI URL',
              description:
                'Represent if a service exposes an API with an optional url to an openapi descriptor',
            },
          ];

          return (
            <LabelAndInput label="Metadata shortcuts">
              <div className="d-flex flex-wrap align-items-stretch" style={{ gap: 6 }}>
                {CORE_BOOL_METADATA.map(({ key, label, description }) => {
                  return (
                    <div style={{ flex: 1, minWidth: '40%' }}>
                      <NgBoxBooleanRenderer
                        rawDisplay
                        description={description}
                        label={label}
                        value={metadata[key]}
                        onChange={(e) => {
                          if (e) {
                            setValue({
                              ...value,
                              metadata: {
                                ...(metadata || {}),
                                [key]: '' + e,
                              },
                            });
                          } else {
                            setValue({
                              ...value,
                              metadata: Object.fromEntries(
                                Object.entries({ ...(metadata || {}) }).filter((f) => f[0] !== key)
                              ),
                            });
                          }
                        }}
                      />
                    </div>
                  );
                })}
                {CORE_STRING_METADATA.map(({ key, label, description }) => {
                  return (
                    <div style={{ flex: 1, minWidth: '40%' }}>
                      <NgBoxBooleanRenderer
                        rawDisplay
                        description={description}
                        label={label}
                        value={metadata[key]}
                        onChange={(e) => {
                          if (e) {
                            setValue({
                              ...value,
                              metadata: {
                                ...(metadata || {}),
                                [key]: 'ENTER YOUR VALUE',
                              },
                            });
                          } else {
                            setValue({
                              ...value,
                              metadata: Object.fromEntries(
                                Object.entries({ ...(metadata || {}) }).filter((f) => f[0] !== key)
                              ),
                            });
                          }
                        }}
                      />
                    </div>
                  );
                })}
              </div>
            </LabelAndInput>
          );
        },
      },
      metadata: {
        type: 'object',
        label: 'Metadata',
      },
      tags: {
        type: 'string',
        array: true,
        label: 'Tags',
      },
      _loc: {
        type: 'location',
        props: {
          label: 'Location',
        },
      },
      greenScoreRules: {
        renderer: GreenScoreForm
      }
    };

    const isOnGreenScorePage = query === "green_score";

    const greenScoreStep = {
      type: 'group',
      name: ({ value }) => {
        const rankInformations = calculateGreenScore(value.green_score_rules);
        return <span>Green score <i className="fa fa-leaf" style={{ color: rankInformations.rank }} /></span>
      },
      collapsable: true,
      collapsed: !isOnGreenScorePage,
      fields: ['greenScoreRules']
    }

    const flow = isOnGreenScorePage ? [greenScoreStep] : [
      {
        type: 'group',
        name: 'Expose your route',
        fields: ['enabled'],
      },
      '_loc',
      {
        type: 'group',
        name: 'Route',
        fields: [
          'name',
          'description',
          'groups',
          {
            type: 'grid',
            name: 'Flags',
            fields: ['debug_flow', 'export_reporting', 'capture'],
          },
        ],
      },
      {
        type: 'group',
        name: 'Misc.',
        collapsed: true,
        fields: ['tags', 'metadata', 'core_metadata'],
      }
    ];

    return (
      <>
        {showAdvancedForm ? (
          <RouteForm
            routeId={routeId}
            setValue={setValue}
            value={value}
            history={history}
            location={location}
            isCreation={isCreation}
          />
        ) : (
          <NgForm
            schema={schema}
            flow={flow}
            value={value}
            onChange={(v) => {
              setValue(v);
            }}
          />
        )}

        <div className="d-flex align-items-center justify-content-end mt-3 p-0">
          {!isOnRouteCompositions && (
            <FormSelector onChange={toggleAdvancedForm} entity={ENTITIES.ROUTES} className="me-1" />
          )}
          <Button
            type="danger"
            className="btn-sm"
            onClick={() => history.push(`/${link}`)}
            text="Cancel"
          />
        </div>
      </>
    );
  }
);
