export const actor=new URLSearchParams(window.location.search).get("actor")??"tenant";
export function useAuth(){return {profile:{id:actor,roles:actor==="admin"?["ADMIN"]:["USER"]},authenticated:true};}
