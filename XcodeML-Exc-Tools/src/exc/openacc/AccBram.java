/* -*- Mode: java; c-basic-offset:2 ; indent-tabs-mode:nil ; -*- */
package exc.openacc;

import exc.block.*;
import exc.object.*;

class AccBram extends AccData {
  AccBram(ACCglobalDecl decl, AccInformation info, PragmaBlock pb) {
    super(decl, info, pb);
  }
  AccBram(ACCglobalDecl decl, AccInformation info, XobjectDef def) {
    super(decl, info, def);
  }

  boolean isAcceptableClause(ACCpragma clauseKind) {
    switch (clauseKind) {
      case ALIGN:
      case DIVIDE:
      case SHADOW:
      case INDEX:
      case PLACE:
        return true;
      default:
        return false;
    }
  }
}
