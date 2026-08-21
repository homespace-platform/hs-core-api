# 1. Create file: .vscode\launch.json
```
{
    "configurations": [
        {
            "type": "java",
            "name": "Spring Boot-HsGatewayServerApplication<hs-gateway-server>",
            "request": "launch",
            "cwd": "${workspaceFolder}",
            "mainClass": "com.hs.gateway.HsGatewayServerApplication",
            "projectName": "hs-gateway-server",
            "args": "",
            "envFile": "${workspaceFolder}/hs-core-gateway/.env",
            "console": "integratedTerminal"
        },
        {
            "type": "java",
            "name": "Spring Boot-HsDiscoveryServerApplication<hs-discovery-server>",
            "request": "launch",
            "cwd": "${workspaceFolder}",
            "mainClass": "com.hs.discovery.HsDiscoveryServerApplication",
            "projectName": "hs-discovery-server",
            "args": "",
            "envFile": "${workspaceFolder}/hs-core-discovery/.env",
            "console": "integratedTerminal"
        },
        {
            "type": "java",
            "name": "Spring Boot-HsApiServiceApplication<hs-api-service>",
            "request": "launch",
            "cwd": "${workspaceFolder}",
            "mainClass": "com.hs.HsApiServiceApplication",
            "projectName": "hs-api-service",
            "args": "",
            "envFile": "${workspaceFolder}/hs-core-api/.env",
            "console": "integratedTerminal"
        }
    ]
}
```

# 2. Setup .env mỗi service con (riêng hs-core-api dùng chung)

# 3. Cài Spring Boot Extension Pack => Dùng Spring Boot Dashboard => chạy tự load .env vào dự án
