import {createServer} from "vite";
import path from "node:path";
import {fileURLToPath} from "node:url";
const root=path.dirname(fileURLToPath(import.meta.url)),workspace=path.resolve(root,"../../.."),web=path.join(workspace,"hs-web-app"),admin=path.join(workspace,"hs-admin-portal");
const server=await createServer({configFile:false,root,optimizeDeps:{force:true,include:["react","react-dom/client","sonner","axios"]},resolve:{alias:[{find:"sonner",replacement:web+"/node_modules/sonner/dist/index.mjs"},{find:"axios",replacement:web+"/node_modules/axios/dist/browser/axios.cjs"},{find:"@/features/auth/useAuth",replacement:path.join(root,"auth.ts")},{find:"@/lib/axios-client",replacement:path.join(root,"axios.ts")},{find:"@/services/admin-complaint.service",replacement:path.join(admin,"src/services/admin-complaint.service.ts")},{find:"@",replacement:web},{find:/^react($|\/)/,replacement:web+"/node_modules/react$1"},{find:/^react-dom($|\/)/,replacement:web+"/node_modules/react-dom$1"}]},esbuild:{jsx:"automatic"},server:{host:"127.0.0.1",port:18198,strictPort:true,fs:{allow:[workspace]},proxy:{"/api/v1":{target:"http://127.0.0.1:18197",rewrite:p=>p.replace(/^\/api\/v1/,"")}}}});
await server.listen();server.printUrls();
for(const sig of ["SIGINT","SIGTERM"])process.on(sig,async()=>{await server.close();process.exit(0);});
