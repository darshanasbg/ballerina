import ballerina/http;

endpoint http:ServiceEndpoint headerServiceEP {
    port: 9090
};

endpoint http:ServiceEndpoint stockServiceEP {
    port: 9091
};

endpoint http:ClientEndpoint stockqEP {
    targets: [{uri: "http://localhost:9091"}]
};

@http:ServiceConfig {
    basePath:"/product"
}
service<http:Service> headerService bind headerServiceEP {

    value (endpoint conn, http:Request req) {
        req.setHeader("core", "aaa");
        req.addHeader("core", "bbb");

        var result = stockqEP -> get("/sample/stocks", req);
        match result {
            http:Response clientResponse => {
                _ = conn -> forward(clientResponse);
            }
            any|null => { return;}
        }
    }

    id (endpoint conn, http:Request req) {
        http:Response clntResponse = {};
        var reply = stockqEP -> forward("/sample/customers", req);

        match reply {
            http:Response clientResponse => {
                json payload = {};
                if (clientResponse.hasHeader("person")) {
                    string[] headers = clientResponse.getHeaders("person");
                    if (lengthof(headers) == 2) {
                        payload = {header1:headers[0], header2:headers[1]};
                    } else {
                        payload = {"response":"expected number of 'person' headers not found"};
                    }
                } else {
                    payload = {"response":"person header not available"};
                }
                http:Response res = {};
                res.setJsonPayload(payload);
                _ = conn -> respond(res);
            }
            any|null => {
                return;
            }
        }
    }
}

@http:ServiceConfig {
    basePath:"/sample"
}
service<http:Service> quoteService bind stockServiceEP {

    @http:ResourceConfig {
        methods:["GET"],
        path:"/stocks"
    }
    company (endpoint conn, http:Request req) {
        json payload = {};
        if (req.hasHeader("core")) {
            string[] headers = req.getHeaders("core");
            if (lengthof(headers) == 2) {
                payload = {header1:headers[0], header2:headers[1]};
            } else {
                payload = {"response":"expected number of 'core' headers not found"};
            }
        } else {
            payload = {"response":"core header not available"};
        }
        http:Response res = {};
        res.setJsonPayload(payload);
        _ = conn -> respond(res);
    }

    @http:ResourceConfig {
        methods:["GET"],
        path:"/customers"
    }
    product (endpoint conn, http:Request req) {
        http:Response res = {};
        res.setHeader("person", "kkk");
        res.addHeader("person", "jjj");
        _ = conn -> respond(res);
    }
}
