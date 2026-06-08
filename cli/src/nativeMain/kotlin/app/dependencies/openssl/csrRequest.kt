package app.dependencies.openssl

fun csrRequestConfigFileContent(cn: String) = """
[req]
distinguished_name = req_distinguished_name
req_extensions = v3_req
prompt = no

[req_distinguished_name]
CN = $cn
C = DE
ST = Saxony
L = Dresden
O = Werkbank

[v3_req]
basicConstraints = critical,CA:TRUE
"""