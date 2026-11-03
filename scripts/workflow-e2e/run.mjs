import {spawn,execFileSync} from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import {fileURLToPath} from "node:url";
const here=path.dirname(fileURLToPath(import.meta.url)),core=path.resolve(here,"../.."),win=process.platform==="win32";
const sleep=ms=>new Promise(r=>setTimeout(r,ms));let ownedContainer,backend,finished=false;
fs.mkdirSync(path.join(here,"artifacts"),{recursive:true});
function run(command,args,options={}){return spawn(command,args,{windowsHide:true,shell:win,cwd:here,stdio:"inherit",...options});}
function exit(child){return new Promise(resolve=>{if(child.exitCode!==null)resolve(child.exitCode);else child.once("exit",resolve);});}
async function finish(passed){try{await fetch("http://127.0.0.1:18197/test-harness/finish",{method:"POST",signal:AbortSignal.timeout(3000),headers:{Authorization:"Bearer hs-workflow-test-owner","Content-Type":"application/json"},body:JSON.stringify({passed})});finished=true;}catch{}}
try{
 let url=process.env.HS_WORKFLOW_HTTP_PG_URL;
 if(!url){const name="hs-workflow-e2e-"+process.pid;ownedContainer=execFileSync("docker",["run","-d","--name",name,"-e","POSTGRES_PASSWORD=hs_workflow_test","-e","POSTGRES_DB=hs_workflow_http_test","-p","127.0.0.1:55445:5432","postgres:16-alpine"],{encoding:"utf8",windowsHide:true}).trim();url="jdbc:postgresql://127.0.0.1:55445/hs_workflow_http_test";await sleep(3000);}
 if(!/^jdbc:postgresql:\/\/127\.0\.0\.1:[0-9]+\/hs_workflow_http_test$/.test(url))throw new Error("Only the dedicated local hs_workflow_http_test DB is allowed");
 const log=fs.openSync(path.join(here,"artifacts/backend.log"),"w");backend=run("mvn",["-Pisolated-build","-pl","hs-api-service","-am","-Dtest=RentalWorkflowHttpSystemTest","-DargLine=-Xmx768m","-Dsurefire.failIfNoSpecifiedTests=false","test"],{cwd:core,env:{...process.env,HS_WORKFLOW_HTTP_PG_URL:url,HS_WORKFLOW_BROWSER_WAIT:"1"},stdio:["ignore",log,log]});
 let ready=false;for(let n=0;n<180;n++){if(backend.exitCode!==null)throw new Error("Test backend exited; see artifacts/backend.log");try{const r=await fetch("http://127.0.0.1:18197/test-harness/ready",{signal:AbortSignal.timeout(1500)});ready=r.ok&&(await r.json()).ready;if(ready)break;}catch{}await sleep(1000);}if(!ready)throw new Error("Test backend did not become ready");
 const code=await exit(run("npx",["playwright","test"]));await finish(code===0);const back=await exit(backend);if(code!==0||back!==0)throw new Error("Workflow browser/system tests failed; inspect artifacts and test-results");
}catch(e){console.error(e.message);process.exitCode=1;}finally{
 if(backend&&backend.exitCode===null){if(!finished)await finish(false);await Promise.race([exit(backend),sleep(10000)]);if(backend.exitCode===null){if(win)execFileSync("taskkill",["/PID",String(backend.pid),"/T","/F"],{windowsHide:true,stdio:"ignore"});else backend.kill("SIGTERM");}}
 if(ownedContainer&&/^[a-f0-9]{64}$/.test(ownedContainer))execFileSync("docker",["rm","-f","-v",ownedContainer],{windowsHide:true,stdio:"ignore"});
}
