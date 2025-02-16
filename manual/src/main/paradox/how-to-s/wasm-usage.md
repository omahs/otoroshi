# Using wasm plugins

WebAssembly (WASM) is a simple machine model and executable format with an extensive specification. It is designed to be portable, compact, and execute at or near native speeds. Otoroshi already supports the execution of WASM files by providing different plugins that can be applied on routes. You can find more about those plugins @ref:[here](../topics/wasm-usage.md)

To simplify the process of WASM creation and usage, Otoroshi provides:

- otoroshi ui integration: a full set of plugins that let you pick which WASM function to runtime at any point in a route
- otoroshi `wasm-manager`: a code editor in the browser that let you write your plugin in `Rust`, `TinyGo`, `Javascript` or `Assembly Script` without having to think about compiling it to WASM (you can find a complete tutorial about it @ref:[here](../how-to-s/wasm-manager-installation.md))

@@@ div { .centered-img }
<img src="../imgs/otoroshi-wasm-manager-1.png" title="screenshot of a wasm manager instance" />
@@@

## Tutorial

1. [Before your start](#before-your-start)
2. [Create the route with the plugin validator](#create-the-route-with-the-plugin-validator)
3. [Test your validator](#test-your-validator)
4. [Update the route by replacing the backend with a WASM file](#update-the-route-by-replacing-the-backend-with-a-wasm-file)
5. [WASM backend test](#wasm-backend-test)

After completing these steps you will have a route that uses WASM plugins written in Rust.

## Before your start

@@include[initialize.md](../includes/initialize.md) { #initialize-otoroshi }

## Create the route with the plugin validator

For this tutorial, we will start with an existing wasm file. The main function of this file will check the value of an http header to allow access or not. The can find this file at [https://raw.githubusercontent.com/MAIF/otoroshi/master/demos/wasm/first-validator.wasm](#https://raw.githubusercontent.com/MAIF/otoroshi/master/demos/wasm/first-validator.wasm)

The main function of this validator, written in rust, should look like:

validator.rs
:   @@snip [validator.rs](../snippets/wasm-manager/validator.rs) 

validator.js
:   @@snip [validator.js](../snippets/wasm-manager/validator.js) 

validator.ts
:   @@snip [validator.ts](../snippets/wasm-manager/validator.ts) 

validator.js
:   @@snip [validator.js](../snippets/wasm-manager/validator.js) 

validator.go
:   @@snip [validator.js](../snippets/wasm-manager/validator.go) 

The plugin receives the request context from Otoroshi (the matching route, the api key if present, the headers, etc) as `WasmAccessValidatorContext` object. 
Then it applies a check on the headers, and responds with an error or success depending on the content of the foo header. 
Obviously, the previous snippet is an example and the editor allows you to write whatever you want as a check.

Let's create a route that uses the previous wasm file as an access validator plugin :

```sh
curl -X POST "http://otoroshi-api.oto.tools:8080/api/routes" \
-H "Content-type: application/json" \
-u admin-api-apikey-id:admin-api-apikey-secret \
-d @- <<'EOF'
{
  "id": "demo-otoroshi",
  "name": "demo-otoroshi",
  "frontend": {
    "domains": ["demo-otoroshi.oto.tools"]
  },
  "backend": {
    "targets": [
      {
        "hostname": "mirror.otoroshi.io",
        "port": 443,
        "tls": true
      }
    ],
    "load_balancing": {
      "type": "RoundRobin"
    }
  },
  "plugins": [
    {
      "plugin": "cp:otoroshi.next.plugins.OverrideHost",
      "enabled": true
    },
    {
      "plugin": "cp:otoroshi.next.plugins.WasmAccessValidator",
      "enabled": true,
      "config": {
        "source": {
          "kind": "http",
          "path": "https://raw.githubusercontent.com/MAIF/otoroshi/master/demos/wasm/first-validator.wasm",
          "opts": {}
        },
        "memoryPages": 4,
        "functionName": "execute"
      }
    }
  ]
}
EOF
```

This request will apply the following process:

* names the route *demo-otoroshi*
* creates a frontend exposed on the `demo-otoroshi.oto.tools` 
* forward requests on one target, reachable at `mirror.otoroshi.io` using TLS on port 443
* adds the *WasmAccessValidator* plugin to validate access based on the foo header to the route

You can validate the route creation by navigating to the [dashboard](http://otoroshi.oto.tools:8080/bo/dashboard/routes/demo-otoroshi?tab=flow)

## Test your validator

```shell
curl "http://demo-otoroshi.oto.tools:8080" -I
```

This should output the following error:

```
HTTP/1.1 401 Unauthorized
```

Let's call again the route by adding the header foo with the bar value.

```shell
curl "http://demo-otoroshi.oto.tools:8080" -H "foo:bar" -I
```

This should output the successfull message:

```
HTTP/1.1 200 OK
```

## Update the route by replacing the backend with a WASM file

The next step in this tutorial is to use a WASM file as backend  of the route. We will use an existing WASM file, available in our wasm demos repository on github. 
The content of this plugin, called `wasm-target.wasm`, looks like:

target.rs
:   @@snip [target.rs](../snippets/wasm-manager/target.rs) 

target.js
:   @@snip [target.js](../snippets/wasm-manager/target.js) 

target.ts
:   @@snip [target.ts](../snippets/wasm-manager/target.ts) 

target.js
:   @@snip [target.js](../snippets/wasm-manager/target.js) 

target.go
:   @@snip [target.js](../snippets/wasm-manager/target.go) 

Let's explain this snippet. The purpose of this type of plugin is to respond an HTTP response with http status, body and headers map.

1. Includes all public structures from `types.rs` file. This file contains predefined Otoroshi structures that plugins can manipulate.
2. Necessary imports. [Extism](https://extism.org/docs/overview)'s goal is to make all software programmable by providing a plug-in system. 
3. Creates a map of new headers that will be merged with incoming request headers.
4. Creates the response object with the map of merged headers, a simple JSON body and a successfull status code.

The file is downloadable [here](#https://raw.githubusercontent.com/MAIF/otoroshi/master/demos/wasm/wasm-target.wasm).

Let's update the route using the this wasm file.

```sh
curl -X PUT "http://otoroshi-api.oto.tools:8080/api/routes/demo-otoroshi" \
-H "Content-type: application/json" \
-u admin-api-apikey-id:admin-api-apikey-secret \
-d @- <<'EOF'
{
  "id": "demo-otoroshi",
  "name": "demo-otoroshi",
  "frontend": {
    "domains": ["demo-otoroshi.oto.tools"]
  },
  "backend": {
    "targets": [
      {
        "hostname": "mirror.otoroshi.io",
        "port": 443,
        "tls": true
      }
    ],
    "load_balancing": {
      "type": "RoundRobin"
    }
  },
  "plugins": [
    {
      "plugin": "cp:otoroshi.next.plugins.OverrideHost",
      "enabled": true
    },
    {
      "plugin": "cp:otoroshi.next.plugins.WasmAccessValidator",
      "enabled": true,
      "config": {
         "source": {
          "kind": "http",
          "path": "https://raw.githubusercontent.com/MAIF/otoroshi/master/demos/wasm/first-validator.wasm",
          "opts": {}
        },
        "memoryPages": 4,
        "functionName": "execute"
      }
    },
    {
      "plugin": "cp:otoroshi.next.plugins.WasmBackend",
      "enabled": true,
      "config": {
         "source": {
          "kind": "http",
          "path": "https://raw.githubusercontent.com/MAIF/otoroshi/master/demos/wasm/wasm-target.wasm",
          "opts": {}
        },
        "memoryPages": 4,
        "functionName": "execute"
      }
    }
  ]
}
EOF
```

The response should contains the updated route content.

## WASM backend test

Let's call our route.

```sh
curl "http://demo-otoroshi.oto.tools:8080" -H "foo:bar" -H "fifi: foo" -v
```

This should output:

```
*   Trying 127.0.0.1:8080...
* Connected to demo-otoroshi.oto.tools (127.0.0.1) port 8080 (#0)
> GET / HTTP/1.1
> Host: demo-otoroshi.oto.tools:8080
> User-Agent: curl/7.79.1
> Accept: */*
> foo:bar
> fifi:foo
>
* Mark bundle as not supporting multiuse
< HTTP/1.1 200 OK
< foo: bar
< Host: demo-otoroshi.oto.tools:8080
<
* Closing connection 0
{"foo": "bar"}
```

In this response, we can find our headers send in the curl command and those added by the wasm plugin.



