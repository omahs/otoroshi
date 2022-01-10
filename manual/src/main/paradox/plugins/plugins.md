# Otoroshi plugins system

Otoroshi includes several extension points that allows you to create your own plugins and support stuff not supported by default

## Available plugins

@@@ div { .plugin .script }
## Request Sink
### Description
Used when no services are matched in Otoroshi. Can reply with any content.
@@@

@@@ div { .plugin .script }
## Pre routing
### Description
Used to extract values (like custom apikeys) and provide them to other plugins or Otoroshi engine
@@@

@@@ div { .plugin .script }
## Access Validator
### Description
Used to validate if a request can pass or not based on whatever you want
@@@

@@@ div { .plugin .script }
## Request Transformer
### Description
Used to transform request, responses and their body. Can be used to return arbitrary content
@@@

@@@ div { .plugin .script }
## Event listener
### Description
Any plugin type can listen to Otoroshi internal events and react to thems
@@@

@@@ div { .plugin .script }
## Job
### Description
Tasks that can run automatically once, on be scheduled with a cron expression or every defined interval
@@@

@@@ div { .plugin .script }
## Exporter
### Description
Used to export events and Otoroshi alerts to an external source
@@@

@@@ div { .plugin .script }
## Request handler
### Description
Used to handle traffic without passing through Otoroshi routing and apply own rules
@@@

@@@ div { .plugin .script }
## Nano app
### Description
Used to write an api directly in Otoroshi in Scala language
@@@