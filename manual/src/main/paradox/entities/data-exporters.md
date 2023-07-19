# Data exporters

The data exporters are the way to export alerts and events from Otoroshi to an external storage.

To try them, you can folllow @ref[this tutorial](../how-to-s/export-alerts-using-mailgun.md).

## Common fields

* `Type`: the type of event exporter
* `Enabled`: enabled or not the exporter
* `Name`: given name to the exporter
* `Description`: the data exporter description
* `Tags`: list of tags associated to the module
* `Metadata`: list of metadata associated to the module

All exporters are split in three parts. The first and second parts are common and the last are specific by exporter.

* `Filtering and projection` : section to filter the list of sent events and alerts. The projection field allows you to export only certain event fields and reduce the size of exported data. It's composed of `Filtering` and `Projection` fields. To get a full usage of this elements, read @ref:[this section](#matching-and-projections)
* `Queue details`: set of fields to adjust the workers of the exporter. 
  * `Buffer size`: if elements are pushed onto the queue faster than the source is consumed the overflow will be handled with a strategy specified by the user. Keep in memory the number of events.
  * `JSON conversion workers`: number of workers used to transform events to JSON format in paralell
  * `Send workers`: number of workers used to send transformed events
  * `Group size`: chunk up this stream into groups of elements received within a time window (the time window is the next field)
  * `Group duration`: waiting time before sending the group of events. If the group size is reached before the group duration, the events will be instantly sent
  
For the last part, the `Exporter configuration` will be detail individually.

## Matching and projections

**Filtering** is used to **include** or **exclude** some kind of events and alerts. For each include and exclude field, you can add a list of key-value. 

Let's say we only want to keep Otoroshi alerts
```json
{ "include": [{ "@type": "AlertEvent" }] }
```

Otoroshi provides a list of rules to keep only events with specific values. We will use the following event to illustrate.

```json
{
 "foo": "bar",
 "type": "AlertEvent",
 "alert": "big-alert",
 "status": 200,
 "codes": ["a", "b"],
 "inner": {
   "foo": "bar",
   "bar": "foo"
 }
}
```

The rules apply with the previous example as event.

@@@div { #filtering }
&nbsp;
@@@



**Projection** is a list of fields to export. In the case of an empty list, all the fields of an event will be exported. In other case, **only** the listed fields will be exported.

Let's say we only want to keep Otoroshi alerts and only type, timestamp and id of each exported events
```json
{
 "@type": true,
 "@timestamp": true,
 "@id": true
}
```

An other possibility is to **rename** the exported field. This value will be the same but the exported field will have a different name.

Let's say we want to rename all `@id` field with `unique-id` as key

```json
{ "@id": "unique-id" }
```

The last possiblity is to retrieve a sub-object of an event. Let's say we want to get the name of each exported user of events.

```json
{ "user": { "name": true } }
```

You can also expand the entire source object with 

```json
{
  "$spread": true
}
```

and the remove fields you don't want with 

```json
{
  "fieldthatidontwant": false
}
```

Projections allows object modification using jspath, for instance, this example will create a new `otoroshiHeaderKeys` field to exported events. This field will contains a string array containing every request header name.

```json
{
  "otoroshiHeaderKeys": {
     "$path": "$.otoroshiHeadersIn.*.key"
  }
}
```

Alternativerly, projections also allow to use JQ to transform exported events

```json
{
  "headerKeys": {
     "$jq": "[.headers[].key]"
  }
}
```

JQ filter also allows conditionnal filtering : transformation is applied only if given predicate is match. In the following example, `headerKeys` field will be valued only if `target.scheme` is `https`.

```json
{
  "headerKeys": {
    "$jqIf": {
      "filter": "[.headers[].key]",
      "predicate": {
        "path": "target.scheme",
        "value": "https"
      }
    }
  }
}
```

See [JQ manual](https://jqlang.github.io/jq/manual/) for complete syntax reference.

## Elastic

With this kind of exporter, every matching event will be sent to an elastic cluster (in batch). It is quite useful and can be used in combination with [elastic read in global config](./global-config.html#analytics-elastic-dashboard-datasource-read-)

* `Cluster URI`: Elastic cluster URI
* `Index`: Elastic index 
* `Type`: Event type (not needed for elasticsearch above 6.x)
* `User`: Elastic User (optional)
* `Password`: Elastic password (optional)
* `Version`: Elastic version (optional, if none provided it will be fetched from cluster)
* `Apply template`: Automatically apply index template
* `Check Connection`: Button to test the configuration. It will displayed a modal with checked point, and if the case of it's successfull, it will displayed the found version of the Elasticsearch and the index used
* `Manually apply index template`: try to put the elasticsearch template by calling the api of elasticsearch
* `Show index template`: try to retrieve the current index template presents in elasticsearch
* `Client side temporal indexes handling`: When enabled, Otoroshi will manage the creation of indexes. When it's disabled, Otoroshi will push in the same index
* `One index per`: When the previous field is enabled, you can choose the interval of time between the creation of a new index in elasticsearch 
* `Custom TLS Settings`: Enable the TLS configuration for the communication with Elasticsearch
  * `TLS loose`: if enabled, will block all untrustful ssl configs
  * `TrustAll`: allows any server certificates even the self-signed ones
  * `Client certificates`: list of client certificates used to communicate with elasticsearch
  * `Trusted certificates`: list of trusted certificates received from elasticsearch

## Webhook 

With this kind of exporter, every matching event will be sent to a URL (in batch) using a POST method and an JSON array body.

* `Alerts hook URL`: url used to post events
* `Hook Headers`: headers add to the post request
* `Custom TLS Settings`: Enable the TLS configuration for the communication with Elasticsearch
  * `TLS loose`: if enabled, will block all untrustful ssl configs
  * `TrustAll`: allows any server certificates even the self-signed ones
  * `Client certificates`: list of client certificates used to communicate with elasticsearch
  * `Trusted certificates`: list of trusted certificates received from elasticsearch


## Pulsar 

With this kind of exporter, every matching event will be sent to an [Apache Pulsar topic](https://pulsar.apache.org/)


* `Pulsar URI`: URI of the pulsar server
* `Custom TLS Settings`: Enable the TLS configuration for the communication with Elasticsearch
  * `TLS loose`: if enabled, will block all untrustful ssl configs
  * `TrustAll`: allows any server certificates even the self-signed ones
  * `Client certificates`: list of client certificates used to communicate with elasticsearch
  * `Trusted certificates`: list of trusted certificates received from elasticsearch
* `Pulsar tenant`: tenant on the pulsar server
* `Pulsar namespace`:  namespace on the pulsar server
* `Pulsar topic`: topic on the pulsar server

## Kafka 

With this kind of exporter, every matching event will be sent to an [Apache Kafka topic](https://kafka.apache.org/). You can find few @ref[tutorials](../how-to-s/communicate-with-kafka.md) about the connection between Otoroshi and Kafka based on docker images.

* `Kafka Servers`: the list of servers to contact to connect the Kafka client with the Kafka cluster
* `Kafka topic`: the topic on which Otoroshi alerts will be sent

By default, Kafka is installed with no authentication. Otoroshi supports the following authentication mechanisms and protocols for Kafka brokers.

### SASL

The Simple Authentication and Security Layer (SASL) [RFC4422] is a
method for adding authentication support to connection-based
protocols.

* `SASL username`: the client username  
* `SASL password`: the client username  
* `SASL Mechanism`: 
     * `PLAIN`: SASL/PLAIN uses a simple username and password for authentication.
     * `SCRAM-SHA-256` and `SCRAM-SHA-512`: SASL/SCRAM uses usernames and passwords stored in ZooKeeper. Credentials are created during installation.

### SSL 

* `Kafka keypass`: the keystore password if you use a keystore/truststore to connect to Kafka cluster
* `Kafka keystore path`: the keystore path on the server if you use a keystore/truststore to connect to Kafka cluster
* `Kafka truststore path`: the truststore path on the server if you use a keystore/truststore to connect to Kafka cluster
* `Custom TLS Settings`: enable the TLS configuration for the communication with Elasticsearch
    * `TLS loose`: if enabled, will block all untrustful ssl configs
    * `TrustAll`: allows any server certificates even the self-signed ones
    * `Client certificates`: list of client certificates used to communicate with elasticsearch
    * `Trusted certificates`: list of trusted certificates received from elasticsearch

### SASL + SSL

This mechanism uses the SSL configuration and the SASL configuration.

## Mailer 

With this kind of exporter, every matching event will be sent in batch as an email (using one of the following email provider)

Otoroshi supports 5 exporters of email type.

### Console

Nothing to add. The events will be write on the standard output.

### Generic

* `Mailer url`: URL used to push events
* `Headers`: headers add to the push requests
* `Email addresses`: recipients of the emails

### Mailgun

* `EU`: is EU server ? if enabled, *https://api.eu.mailgun.net/* will be used, otherwise, the US URL will be used : *https://api.mailgun.net/*
* `Mailgun api key`: API key of the mailgun account
* `Mailgun domain`: domain name of the mailgun account
* `Email addresses`: recipients of the emails

### Mailjet

* `Public api key`: public key of the mailjet account
* `Private api key`: private key of the mailjet account
* `Email addresses`: recipients of the emails

### Sendgrid

* `Sendgrid api key`: api key of the sendgrid account
* `Email addresses`: recipients of the emails

## File 

* `File path`: path where the logs will be write 
* `Max file size`: when size is reached, Otoroshi will create a new file postfixed by the current timestamp

## GoReplay file

With this kind of exporter, every matching event will be sent to a `.gor` file compatible with [GoReplay](https://goreplay.org/). 

@@@ warning
this exporter will only be able to catch `TrafficCaptureEvent`. Those events are created when a route (or the global config) of the @ref:[new proxy engine](../topics/engine.md) is setup to capture traffic using the `capture` flag.
@@@

* `File path`: path where the logs will be write 
* `Max file size`: when size is reached, Otoroshi will create a new file postfixed by the current timestamp
* `Capture requests`: capture http requests in the `.gor` file
* `Capture responses`: capture http responses in the `.gor` file

## Console 

Nothing to add. The events will be write on the standard output.

## Custom 

This type of exporter let you the possibility to write your own exporter with your own rules. To create an exporter, we need to navigate to the plugins page, and to create a new item of type exporter.

When it's done, the exporter will be visible in this list.

* `Exporter config.`: the configuration of the custom exporter.

## Metrics 

This plugin is useful to rewrite the metric labels exposed on the `/metrics` endpoint.

* `Labels`: list of metric labels. Each pair contains an existing field name and the new name.