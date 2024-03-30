/* -*- Mode: java; c-basic-offset:2 ; indent-tabs-mode:nil ; -*- */
package exc.openacc;

import exc.block.*;
import exc.object.*;

class AccAllgather extends AccData {
  AccAllgather(ACCglobalDecl decl, AccInformation info, PragmaBlock pb) {
    super(decl, info, pb);
  }
  AccAllgather(ACCglobalDecl decl, AccInformation info, XobjectDef def) {
    super(decl, info, def);
  }

  boolean isAcceptableClause(ACCpragma clauseKind) {
    return clauseKind == ACCpragma.ALLGATHER;
  }
}
