/* -*- Mode: java; c-basic-offset:2 ; indent-tabs-mode:nil ; -*- */
package exc.openacc;

import exc.block.*;
import exc.object.*;

class AccReflect extends AccData {
  AccReflect(ACCglobalDecl decl, AccInformation info, PragmaBlock pb) {
    super(decl, info, pb);
  }
  AccReflect(ACCglobalDecl decl, AccInformation info, XobjectDef def) {
    super(decl, info, def);
  }

  boolean isAcceptableClause(ACCpragma clauseKind) {
    // No clause acceptable.
    return clauseKind == ACCpragma.REFLECT;
  }
}
