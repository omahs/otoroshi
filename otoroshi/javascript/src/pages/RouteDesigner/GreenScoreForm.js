import React from 'react';

export default function GreenScoreForm(props) {

  console.log(props)

  const sections = rootValue?.green_score_rules || [];

  return <div>
    {sections.map((rule, i) => {
      return <div key={rule.id}>
        
      </div>
    })}
  </div>
}

// type: 'form',
//         label: 'Check only the rules for which your service respects them. These values will be use to calculate the green score of your route',
//         schema: {
//           "AR01": {
//             "type": "box-boolean",
//             "label": "AR01",
//             "props": {
//               "description": "Use Event Driven Architecture to avoid polling madness and inform subscribers of an update. Use Event Driven Architecture to avoid polling madness."
//             }
//           },
//           "AR02": {
//             "type": "box-boolean",
//             "label": "AR02",
//             "props": {
//               "description": "API runtime close to the Consumer Deploy the API near the consumer"
//             }
//           },
//           "AR03": {
//             "type": "box-boolean",
//             "label": "AR03",
//             "props": {
//               "description": "Ensure the same API does not exist *. Ensure only one API fit the same need"
//             }
//           },
//           "AR04": {
//             "type": "box-boolean",
//             "label": "AR04",
//             "props": {
//               "description": "Use scalable infrastructure to avoid over-provisioning. Use scalable infrastructure to avoid over-provisioning"
//             }
//           },
//           "DE01": {
//             "type": "box-boolean",
//             "label": "DE01",
//             "props": {
//               "description": "Choose an exchange format with the smallest size (JSON is smallest than XML. Prefer an exchange format with the smallest size (JSON is smaller than XML)."
//             }
//           },
//           "DE02": {
//             "type": "box-boolean",
//             "label": "DE02",
//             "props": {
//               "description": "new API --> cache usage. Use cache to avoid useless requests and preserve compute resources."
//             }
//           },
//           "DE03": {
//             "type": "box-boolean",
//             "label": "DE03",
//             "props": {
//               "description": "Existing API --> cache usage efficiency. Use the cache efficiently to avoid useless resources consumtion."
//             }
//           },
//           "DE04": {
//             "type": "box-boolean",
//             "label": "DE04",
//             "props": {
//               "description": "Opaque token usage. Prefer opaque token usage prior to JWT"
//             }
//           },
//           "DE05": {
//             "type": "box-boolean",
//             "label": "DE05",
//             "props": {
//               "description": "Align the cache refresh with the datasource **. Align cache refresh strategy with the data source "
//             }
//           },
//           "DE06": {
//             "type": "box-boolean",
//             "label": "DE06",
//             "props": {
//               "description": "Allow part refresh of cache. Allow a part cache refresh"
//             }
//           },
//           "DE07": {
//             "type": "box-boolean",
//             "label": "DE07",
//             "props": {
//               "description": "Is System,  Business or cx API ?. Use Business & Cx APIs closer to the business need"
//             }
//           },
//           "DE08": {
//             "type": "box-boolean",
//             "label": "DE08",
//             "props": {
//               "description": "Possibility to filter results. Implement filtering mechanism to limit the payload size"
//             }
//           },
//           "DE09": {
//             "type": "box-boolean",
//             "label": "DE09",
//             "props": {
//               "description": "Leverage OData or GraphQL for your databases APIs. Leverage OData or GraphQL when relevant"
//             }
//           },
//           "DE10": {
//             "type": "box-boolean",
//             "label": "DE10",
//             "props": {
//               "description": "Redundant data information in the same API. Avoid redundant data information in the same API"
//             }
//           },
//           "DE11": {
//             "type": "box-boolean",
//             "label": "DE11",
//             "props": {
//               "description": "Possibility to fitler pagination results. Implement pagination mechanism to limit the payload size"
//             }
//           },
//           "US01": {
//             "type": "box-boolean",
//             "label": "US01",
//             "props": {
//               "description": "Use query parameters for GET Methods. Implement filters to limit which data are returned by the API (send just the data the consumer need)."
//             }
//           },
//           "US02": {
//             "type": "box-boolean",
//             "label": "US02",
//             "props": {
//               "description": "Decomission end of life or not used APIs. Decomission end of life or not used APIs"
//             }
//           },
//           "US03": {
//             "type": "box-boolean",
//             "label": "US03",
//             "props": {
//               "description": "Number of API version <=2 . Compute resources saved & Network impact reduced"
//             }
//           },
//           "US04": {
//             "type": "box-boolean",
//             "label": "US04",
//             "props": {
//               "description": "Usage of Pagination of results available. Optimize queries to limit the information returned to what is strictly necessary."
//             }
//           },
//           "US05": {
//             "type": "box-boolean",
//             "label": "US05",
//             "props": {
//               "description": "Choosing relevant data representation (user don’t need to do multiple calls) is Cx API ?. Choose the correct API based on use case to avoid requests on multiple systems or large number of requests. Refer to the data catalog to validate the data source."
//             }
//           },
//           "US06": {
//             "type": "box-boolean",
//             "label": "US06",
//             "props": {
//               "description": "Number of Consumers. Deploy an API well designed and documented to increase the reuse rate. Rate based on number of different consumers"
//             }
//           },
//           "US07": {
//             "type": "box-boolean",
//             "label": "US07",
//             "props": {
//               "description": "Error rate. Monitor and decrease the error rate to avoid over processing"
//             }
//           },
//           "LO01": {
//             "type": "box-boolean",
//             "label": "LO01",
//             "props": {
//               "description": "Logs retention. Align log retention period to the business need (ops and Legal)"
//             }
//           }