import { createContext, useContext } from "react";
import type { Session } from "./lib/api";
export const Workspace = createContext<{
  session: Session;
  merchant: string;
}>({
  session: {
    actor: "",
    merchantId: "",
    roles: [],
    csrfToken: "",
    csrfHeader: "",
  },
  merchant: "",
});
export const useWorkspace = () => useContext(Workspace);
