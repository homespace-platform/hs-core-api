import React from "react";
import {createRoot} from "react-dom/client";
import {Toaster} from "sonner";
import RentalWorkflowPanel from "../../../hs-web-app/components/rental-request/RentalWorkflowPanel";
import ComplaintPanel from "../../../hs-web-app/components/complaint/ComplaintPanel";
import RentalComplaintsPanel from "../../../hs-admin-portal/src/pages/operations/RentalComplaintsPanel";
const query=new URLSearchParams(location.search),rental=query.get("rental")??"",actor=query.get("actor")??"tenant",kind=query.get("kind")??"move-in";
createRoot(document.getElementById("root")!).render(<main><h1>HomeSpace · môi trường kiểm thử riêng · {actor}</h1>{actor==="admin"?<RentalComplaintsPanel/>:<><RentalWorkflowPanel rentalRequestId={rental} kind={kind as "move-in"|"early-exit"|"cancellation"}/><ComplaintPanel rentalRequestId={rental} attention/></>}<Toaster/></main>);
const css=document.createElement("style");css.textContent="body{font:14px Arial;line-height:1.5;background:#f7fafc;color:#10213a;margin:24px}main{max-width:1080px;margin:auto}section,details,article{padding:16px;margin:12px 0;border:1px solid #ccd6e3;border-radius:10px;background:white}button,select,input,textarea{font:inherit;padding:8px;margin:5px;border:1px solid #9aaec6;border-radius:6px}button{cursor:pointer}button:disabled{opacity:.5;cursor:default}textarea{display:block;width:90%}table{border-collapse:collapse;width:100%}td,th{padding:8px;border:1px solid #d3dce6;text-align:left}label{display:block;margin:8px 0}input[type=checkbox]{width:auto}h1{font-size:20px}";document.head.appendChild(css);
