import React from 'react';
import { Link, useHistory, useParams } from 'react-router-dom';
import { Table } from '../../components/inputs';
import { nextClient } from '../../services/BackOfficeServices';
import { MAX_GREEN_SCORE_NOTE, calculateGreenScore, useEntityFromURI } from '../../util';

export function RoutesTable(props) {
  const params = useParams();
  const history = useHistory();
  const entity = useEntityFromURI();

  const domainColumn = {
    title: 'Frontend',
    filterId: 'frontend.domains.0',
    cell: (item, a) => {
      return (
        <>
          {item.frontend.domains[0] || '-'}{' '}
          {item.frontend.domains.length > 1 && (
            <span
              className="badge bg-secondary"
              style={{ cursor: 'pointer' }}
              title={item.frontend.domains.map((v) => ` - ${v}`).join('\n')}>
              {item.frontend.domains.length - 1} more
            </span>
          )}
        </>
      );
    },
  };

  const targetColumn = {
    title: 'Backend',
    filterId: 'backend.targets.0.hostname',
    cell: (item) => {
      return (
        <>
          {item.backend.targets[0]?.hostname || '-'}{' '}
          {item.backend.targets.length > 1 && (
            <span
              className="badge bg-secondary"
              style={{ cursor: 'pointer' }}
              title={item.backend.targets
                .map((v) => ` - ${v.tls ? 'https' : 'http'}://${v.hostname}:${v.port}`)
                .join('\n')}>
              {item.backend.targets.length - 1} more
            </span>
          )}
        </>
      );
    },
  };

  const exposedColumn = {
    title: 'Enabled',
    id: 'enabled',
    style: { textAlign: 'center', width: 70 },
    notFilterable: true,
    cell: (_, item) =>
      item.enabled ? (
        <span className="fas fa-check-circle" style={{ color: 'var(--color-green)' }} />
      ) : (
        <span className="fas fa-times" style={{ color: 'var(--color-red)' }} />
      ),
  };

  const greenScoreColumn = {
    title: 'Green Score',
    id: 'green_score',
    style: {
      textAlign: 'center',
      width: 100
    },
    notFilterable: true,
    cell: (_, item) => {
      const rankInformations = calculateGreenScore(item.green_score_rules);
      console.log(rankInformations)
      return <>
        {Math.round(rankInformations.score / MAX_GREEN_SCORE_NOTE * 100)}
        <i className="fa fa-leaf ms-2" style={{ color: rankInformations.rank }} />
      </>
    }
  }

  const columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
      wrappedCell: (v, item, table) => {
        if (props.globalEnv && props.globalEnv.adminApiId === item.id) {
          return (
            <span
              title="This route is the API that drives the UI you're currently using. Without it, Otoroshi UI won't be able to work and anything that uses Otoroshi admin API too. You might not want to delete it"
              className="badge bg-danger">
              {item.name}
            </span>
          );
        }
        return item.name;
      },
    },
    entity.lowercase == 'route' ? domainColumn : undefined,
    entity.lowercase == 'route' ? targetColumn : undefined,
    exposedColumn,
    greenScoreColumn
  ].filter((c) => c);

  const deleteItem = (item, table) => {
    if (props.globalEnv.adminApiId === item.id) {
      return window
        .newConfirm(
          `The route you're trying to delete is the Otoroshi Admin API that drives the UI you're currently using. Without it, Otoroshi UI won't be able to work and anything that uses Otoroshi admin API too. Do you really want to do that ?`
        )
        .then((ok1) => {
          if (ok1) {
            window.newConfirm(`Are you sure you really want to do that ?`).then((ok2) => {
              if (ok1 && ok2) {
                nextClient.remove(nextClient.ENTITIES[entity.fetchName], item).then(() => {
                  // table.update();
                });
              }
            });
          }
        });
    } else {
      return nextClient.remove(nextClient.ENTITIES[entity.fetchName], item).then(() => {
        // table.update();
      });
    }
  };

  return (
    <div className="designer">
      <Table
        parentProps={{ params }}
        navigateTo={(item) => history.push(`/${entity.link}/${item.id}?tab=flow`)}
        navigateOnEdit={(item) => history.push(`/${entity.link}/${item.id}?tab=informations`)}
        selfUrl={entity.link}
        defaultTitle={entity.capitalizePlural}
        itemName={entity.capitalize}
        formSchema={null}
        formFlow={null}
        columns={columns}
        deleteItem={(item) => deleteItem(item)}
        fetchItems={(paginationState) =>
          nextClient.findAllWithPagination(nextClient.ENTITIES[entity.fetchName], {
            ...paginationState,
            fields: ['name', 'enabled', 'frontend.domains', 'backend.targets', 'id', 'green_score_rules'],
          })
        }
        showActions={true}
        showLink={false}
        extractKey={(item) => item.id}
        rowNavigation={true}
        hideAddItemAction={true}
        rawEditUrl={true}
        displayTrash={(item) => item.id === props.globalEnv.adminApiId}
        injectTopBar={() => (
          <div className="btn-group input-group-btn">
            <Link className="btn btn-primary" to={`${entity.link}/new?tab=informations`}>
              <i className="fas fa-plus-circle" /> Create new {entity.lowercase}
            </Link>
            {props.injectTopBar}
          </div>
        )}
      />
    </div>
  );
}
