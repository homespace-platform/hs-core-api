import axios from "axios";
import {actor} from "./auth";
const client=axios.create({headers:{Authorization:"Bearer hs-workflow-test-"+actor}});
export default client;
