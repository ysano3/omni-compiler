MODULE mod_230

CONTAINS

   SUBROUTINE sub1
     IMPLICIT NONE
     CALL foo(sub2)
   END SUBROUTINE Sub1

   SUBROUTINE sub2
   END SUBROUTINE Sub2

END MODULE mod_230
