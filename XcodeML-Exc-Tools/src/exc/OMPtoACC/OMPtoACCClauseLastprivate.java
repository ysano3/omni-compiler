package exc.OMPtoACC;

import exc.object.*;
import exc.openmp.*;
import exc.openacc.ACCpragma;

public class OMPtoACCClauseLastprivate extends OMPtoACCClause {
    public OMPtoACCClauseLastprivate() {
        super();
    }

    @Override
    public XobjList convert(Xobject xobj,
                            XobjList clause) {
        if (clause.Nargs() != 2) {
            OMP.error((LineNo)xobj.getLineNo(),
                      "Number of clauses is large or small.");
            return null;
        }

        return Xcons.List(Xcons.String(ACCpragma.OMP_LASTPRIVATE.toString()),
                          clause.getArg(1));
    }
}
