/* -*- Mode: java; c-basic-offset:2 ; indent-tabs-mode:nil ; -*- */
package exc.openacc;

import exc.block.*;
import exc.object.*;

class AccBcast extends AccData {
  AccBcast(ACCglobalDecl decl, AccInformation info, PragmaBlock pb) {
    super(decl, info, pb);
  }
  AccBcast(ACCglobalDecl decl, AccInformation info, XobjectDef def) {
    super(decl, info, def);
  }

  boolean isAcceptableClause(ACCpragma clauseKind) {
    // No clause acceptable.
    return clauseKind == ACCpragma.BCAST;
  }
}
