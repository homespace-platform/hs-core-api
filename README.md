# 1. Create file: .vscode\launch.json
```
{
    "configurations": [
        {
            "type": "java",
            "name": "Spring Boot-HsApiServiceApplication<com.hs-hs-api-service>",
            "request": "launch",
            "cwd": "${workspaceFolder}",
            "mainClass": "com.hs.HsApiServiceApplication",
            "projectName": "com.hs-hs-api-service",
            "args": "",
            "envFile": "${workspaceFolder}/.env",
            "console": "integratedTerminal"
        },
        {
            "type": "java",
            "name": "Spring Boot-HsDiscoveryServerApplication<com.hs-hs-discovery-server>",
            "request": "launch",
            "cwd": "${workspaceFolder}",
            "mainClass": "com.hs.discovery.HsDiscoveryServerApplication",
            "projectName": "com.hs-hs-discovery-server",
            "args": "",
            "envFile": "${workspaceFolder}/.env",
            "console": "integratedTerminal"
        },
        {
            "type": "java",
            "name": "Spring Boot-HsGatewayServerApplication<com.hs-hs-gateway-server>",
            "request": "launch",
            "cwd": "${workspaceFolder}",
            "mainClass": "com.hs.gateway.HsGatewayServerApplication",
            "projectName": "com.hs-hs-gateway-server",
            "args": "",
            "envFile": "${workspaceFolder}/.env",
            "console": "integratedTerminal"
        },
        ...
    ]
}
```

# 2. Setup .env mỗi service con (riêng hs-core-api dùng chung)

# 3. Cài Spring Boot Extension Pack => Dùng Spring Boot Dashboard => chạy tự load .env vào dự án
