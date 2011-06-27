/*
 * $TSUKUBA_Release: $
 * $TSUKUBA_Copyright:
 *  $
 */

package exc.xcalablemp;

import exc.block.*;
import exc.object.*;
import java.io.*;
import java.util.*;

public class XMPgpuDecompiler {
  public static final String GPU_FUNC_CONF = "XCALABLEMP_GPU_FUNC_CONF_PROP";
  public static final String GPU_INDEX_TABLE = "XCALABLEMP_GPU_INDEX_TABLE_PROP";

  private static XMPgpuDecompileWriter out = null;
  private static final int BUFFER_SIZE = 4096;
  private static final String GPU_SRC_EXTENSION = ".cu";

  private static HashMap<String, XobjList> _gtolIdHash = null;
  private static HashMap<String, XobjList> _accIdHash = null;

  public static void decompile(Ident hostFuncId, XobjList paramIdList, ArrayList<XMPalignedArray> alignedArrayList,
                               CforBlock loopBlock, PragmaBlock pb, XobjectFile env) throws XMPexception {
    XobjList loopDecl = (XobjList)pb.getClauses();
    XobjList gpuClause = (XobjList)loopDecl.getArg(3).getArg(1);

    XMPpair<XobjList, XobjList> localVars = new XMPpair<XobjList, XobjList>(Xcons.List(), Xcons.List());

    _gtolIdHash = new HashMap<String, XobjList>();
    _accIdHash = new HashMap<String, XobjList>();
    for (XMPalignedArray alignedArray : alignedArrayList) {
      int dim = alignedArray.getDim();
      String alignedArrayName = alignedArray.getName();

      XobjList gtolIdList = Xcons.List();
      XobjList accIdList = Xcons.List();

      for (int i = 0; i < dim; i++) {
        Ident gtolId = Ident.Local(new String("_XMP_GPU_" + alignedArrayName + "_GTOL_" + i), Xtype.intType);
        addLocalVar(gtolId, localVars);
        gtolIdList.add(gtolId);

        if (i != (dim - 1)) {
          Ident accId = Ident.Local(new String("_XMP_GPU_" + alignedArrayName + "_ACC_" + i), Xtype.unsignedlonglongType);
          addLocalVar(accId, localVars);
          accIdList.add(accId);
        }
      }

      _gtolIdHash.put(alignedArrayName, gtolIdList);
      _accIdHash.put(alignedArrayName, accIdList);
    }

    // schedule iteration
    XobjList loopIndexList = (XobjList)loopDecl.getArg(0);
    XobjList loopIndexAddrList = Xcons.List();
    XobjList loopIterRefList = Xcons.List();
    Iterator<Xobject> iter = loopIndexList.iterator();
    while(iter.hasNext()) {
      String loopVarName = iter.next().getString();
      Ident loopVarId = loopBlock.findVarIdent(loopVarName);

      addLocalVar(loopVarId, localVars);

      XobjList loopIter = XMPutil.getLoopIter(loopBlock, loopVarName);

      Ident initId = (Ident)loopIter.getArg(0);
      Ident condId = (Ident)loopIter.getArg(1);
      Ident stepId = (Ident)loopIter.getArg(2);

      loopIndexAddrList.add(loopVarId.getAddr());
      loopIterRefList.add(initId.Ref());
      loopIterRefList.add(condId.Ref());
      loopIterRefList.add(stepId.Ref());
    }

    XMPpair<XobjList, XobjList> deviceFuncParamArgs = genDeviceFuncParamArgs(paramIdList);
    XobjList deviceFuncParams = deviceFuncParamArgs.getFirst();
    XobjList deviceFuncArgs = deviceFuncParamArgs.getSecond();

    // generate wrapping function
    Ident blockXid = Ident.Local("_XMP_GPU_DIM3_block_x", Xtype.intType);
    Ident blockYid = Ident.Local("_XMP_GPU_DIM3_block_y", Xtype.intType);
    Ident blockZid = Ident.Local("_XMP_GPU_DIM3_block_z", Xtype.intType);
    Ident threadXid = Ident.Local("_XMP_GPU_DIM3_thread_x", Xtype.intType);
    Ident threadYid = Ident.Local("_XMP_GPU_DIM3_thread_y", Xtype.intType);
    Ident threadZid = Ident.Local("_XMP_GPU_DIM3_thread_z", Xtype.intType);

    String confParamFuncName = null;
    XobjList numThreads = getNumThreads(gpuClause);
    if (numThreads == null) {
      confParamFuncName = new String("_XMP_gpu_calc_config_params");
    } else {
      confParamFuncName = new String("_XMP_gpu_calc_config_params_NUM_THREADS");
    }

    Ident totalIterId = Ident.Local("_XMP_GPU_TOTAL_ITER", Xtype.unsignedlonglongType);
    Ident confParamFuncId = XMP.getMacroId(confParamFuncName);
    XobjList confParamFuncArgs = Xcons.List(totalIterId.getAddr(),
                                            blockXid.getAddr(), blockYid.getAddr(), blockZid.getAddr(),
                                            threadXid.getAddr(), threadYid.getAddr(), threadZid.getAddr());
    XMPutil.mergeLists(confParamFuncArgs, numThreads);
    XMPutil.mergeLists(confParamFuncArgs, loopIterRefList);
    Xobject confParamFuncCall = Xcons.List(Xcode.EXPR_STATEMENT, confParamFuncId.Call(confParamFuncArgs));

    Ident deviceFuncId = XMP.getMacroId(hostFuncId.getName() + "_DEVICE");
    ((FunctionType)deviceFuncId.Type()).setFuncParamIdList(deviceFuncParams);
    Xobject deviceFuncCall = deviceFuncId.Call(deviceFuncArgs);
    deviceFuncCall.setProp(GPU_FUNC_CONF,
                           (Object)Xcons.List(blockXid, blockYid, blockZid,
                                              threadXid, threadYid, threadZid));

    XobjList hostBodyIdList = Xcons.List(totalIterId, blockXid, blockYid, blockZid, threadXid, threadYid, threadZid);
    XobjList hostBodyDecls = Xcons.List(Xcons.List(Xcode.VAR_DECL, totalIterId, null, null),
                                        Xcons.List(Xcode.VAR_DECL, blockXid, null, null),
                                        Xcons.List(Xcode.VAR_DECL, blockYid, null, null),
                                        Xcons.List(Xcode.VAR_DECL, blockZid, null, null),
                                        Xcons.List(Xcode.VAR_DECL, threadXid, null, null),
                                        Xcons.List(Xcode.VAR_DECL, threadYid, null, null),
                                        Xcons.List(Xcode.VAR_DECL, threadZid, null, null));

    // create Defs
    Xobject hostBodyObj = Xcons.CompoundStatement(hostBodyIdList, hostBodyDecls, Xcons.List(confParamFuncCall, deviceFuncCall));
    ((FunctionType)hostFuncId.Type()).setFuncParamIdList(paramIdList);
    XobjectDef hostDef = XobjectDef.Func(hostFuncId, paramIdList, null, hostBodyObj);

    Ident threadNumId = Ident.Local("_XMP_GPU_THREAD_ID", Xtype.unsignedlonglongType);
    addLocalVar(threadNumId, localVars);
    BlockList newLoopBlockList = Bcons.emptyBody(localVars.getFirst(), localVars.getSecond());

    XobjList calcIterFuncArgs = Xcons.List(threadNumId.Ref());
    XMPutil.mergeLists(calcIterFuncArgs, loopIterRefList);
    XMPutil.mergeLists(calcIterFuncArgs, loopIndexAddrList);

    for (XMPalignedArray alignedArray : alignedArrayList) {
      String alignedArrayName = alignedArray.getName();

      XMPgpuData gpuData = XMPgpuDataTable.findXMPgpuData(alignedArrayName, loopBlock);
      XobjList getArrayInfoFuncArgs = Xcons.List(gpuData.getDeviceDescId().Ref());
      XobjList gtolIdList = _gtolIdHash.get(alignedArrayName);
      for (Xobject x : gtolIdList) {
        getArrayInfoFuncArgs.add(((Ident)x).Ref());
      }

      XobjList accIdList = _accIdHash.get(alignedArrayName);
      for (Xobject x : accIdList) {
        getArrayInfoFuncArgs.add(((Ident)x).Ref());
      }

      newLoopBlockList.add(createFuncCallBlock(new String("_XMP_GPU_M_GET_ARRAY_INFO_" + alignedArray.getDim()),
                                               getArrayInfoFuncArgs));
    }

    newLoopBlockList.add(createFuncCallBlock("_XMP_gpu_calc_thread_id", Xcons.List(threadNumId.getAddr())));
    newLoopBlockList.add(createFuncCallBlock("_XMP_gpu_calc_iter", calcIterFuncArgs));
    rewriteLoopBody(loopBlock, newLoopBlockList);
    newLoopBlockList.add(Xcons.List(Xcode.IF_STATEMENT,
                                    Xcons.binaryOp(Xcode.LOG_LT_EXPR, threadNumId.Ref(), totalIterId.Ref()),
                                                   loopBlock.getBody().toXobject(), null));

    Xobject deviceBodyObj = newLoopBlockList.toXobject();
    XobjectDef deviceDef = XobjectDef.Func(deviceFuncId, deviceFuncParams, null, deviceBodyObj);

    try {
      if (out == null) {
        Writer w = new BufferedWriter(new FileWriter(getSrcName(env.getSourceFileName()) + GPU_SRC_EXTENSION), BUFFER_SIZE);
        out = new XMPgpuDecompileWriter(w, env);
      }

      // add header include line
      out.println("#include \"xmp_gpu_func.hpp\"");
      out.println();
      out.println("#include \"xmp_index_macro.h\"");
      out.println();

      // decompile device function
      out.printDeviceFunc(deviceDef, deviceFuncId);
      out.println();

      // decompie wrapping function
      out.printHostFunc(hostDef);
      out.println();

      out.flush();
    } catch (IOException e) {
      throw new XMPexception("error in gpu decompiler: " + e.getMessage());
    }
  }

  private static void addLocalVar(Ident id, XMPpair<XobjList, XobjList> vars) {
    vars.getFirst().add(id);
    vars.getSecond().add(Xcons.List(Xcode.VAR_DECL, id, null, null));
  }

  private static Block createFuncCallBlock(String funcName, XobjList funcArgs) {
    Ident funcId = XMP.getMacroId(funcName);
    return Bcons.Statement(funcId.Call(funcArgs));
  }

  private static String getSrcName(String srcName) {
    String name = "";
    String[] buffer = srcName.split("\\.");
    for (int i = 0; i < buffer.length - 1; i++) {
      name += buffer[i];
    }

    return name;
  }

  private static XMPpair<XobjList, XobjList> genDeviceFuncParamArgs(XobjList paramIdList) {
    XobjList funcParams = Xcons.List();
    XobjList funcArgs = Xcons.List();
    for (XobjArgs i = paramIdList.getArgs(); i != null; i = i.nextArgs()) {
      Ident id = (Ident)i.getArg();

      funcParams.add(id);

      if (id.Type().isArray()) {
        funcArgs.add(id.getValue());
      } else {
        funcArgs.add(id.Ref());
      }
    }

    Ident totalIterId = Ident.Param("_XMP_GPU_TOTAL_ITER", Xtype.unsignedlonglongType);
    funcParams.add(totalIterId);
    funcArgs.add(totalIterId.Ref());

    return new XMPpair<XobjList, XobjList>(funcParams, funcArgs);
  }

  private static XobjList getNumThreads(XobjList gpuClause) {
    XobjList numThreads = null;

    for (Xobject c : gpuClause) {
      XMPpragma p = XMPpragma.valueOf(c.getArg(0));
      switch (p) {
        case GPU_NUM_THREADS:
          numThreads = (XobjList)c.getArg(1);
        default:
      }
    }

    return numThreads;
  }

  // FIXME localVars, localDecls delete
  private static void rewriteLoopBody(CforBlock loopBlock, BlockList bl) throws XMPexception {
    XMPgenSym symGen = new XMPgenSym();

    BasicBlockExprIterator iter = new BasicBlockExprIterator(loopBlock.getBody());
    for (iter.init(); !iter.end(); iter.next()) {
      rewriteExpr(iter.getExpr(), loopBlock, bl, symGen);
    }
  }

  // FIXME only for one dimension
  private static void rewriteExpr(Xobject expr, CforBlock loopBlock, BlockList bl, XMPgenSym symGen) throws XMPexception {
    if (expr == null) return;

    bottomupXobjectIterator iter = new bottomupXobjectIterator(expr);
    for (iter.init(); !iter.end(); iter.next()) {
      Xobject myExpr = iter.getXobject();
      if (myExpr == null) {
        continue;
      }

      switch (myExpr.Opcode()) {
        case ARRAY_REF:
          {
            String varName = myExpr.getString();
            XMPgpuData gpuData = XMPgpuDataTable.findXMPgpuData(varName, loopBlock);
            XMPalignedArray alignedArray = gpuData.getXMPalignedArray();
            if (alignedArray != null) {
              if (alignedArray.realloc()) {
                iter.next();
                rewriteAlignedArrayExpr(iter, gpuData);
              }
            }
          } break;
        default:
      }
    }
  }

  private static void rewriteAlignedArrayExpr(bottomupXobjectIterator iter, XMPgpuData gpuData) throws XMPexception {
    XobjList funcArgs = Xcons.List(gpuData.getHostId().getAddr());
    parseArrayExpr(iter, gpuData, 0, funcArgs);
  }

  private static void parseArrayExpr(bottomupXobjectIterator iter,
                                     XMPgpuData gpuData, int arrayDimCount, XobjList args) throws XMPexception {
    String syntaxErrMsg = "syntax error on array expression, cannot rewrite distributed array";
    Xobject prevExpr = iter.getPrevXobject();
    Xcode prevExprOpcode = prevExpr.Opcode();
    Xobject myExpr = iter.getXobject();
    Xobject parentExpr = iter.getParent();
    switch (myExpr.Opcode()) {
      case PLUS_EXPR:
        {
          switch (prevExprOpcode) {
            case ARRAY_REF:
              {
                if (arrayDimCount != 0)
                  throw new XMPexception(syntaxErrMsg);
              } break;
            case POINTER_REF:
              break;
            default:
              throw new XMPexception(syntaxErrMsg);
          }

          if (parentExpr.Opcode() == Xcode.POINTER_REF) {
            args.add(getCalcIndexFuncRef(gpuData, arrayDimCount, myExpr.right()));
            iter.next();
            parseArrayExpr(iter, gpuData, arrayDimCount + 1, args);
          } else {
            if (gpuData.getXMPalignedArray().getDim() == arrayDimCount) {
              Xobject funcCall = createRewriteAlignedArrayFunc(gpuData, arrayDimCount, args, Xcode.POINTER_REF);
              myExpr.setLeft(funcCall);
            } else {
              args.add(getCalcIndexFuncRef(gpuData, arrayDimCount, myExpr.right()));
              Xobject funcCall = createRewriteAlignedArrayFunc(gpuData, arrayDimCount + 1, args, Xcode.PLUS_EXPR);
              iter.setXobject(funcCall);
            }

            iter.next();
          }

          return;
        }
      case POINTER_REF:
        {
          switch (prevExprOpcode) {
            case PLUS_EXPR:
              break;
            default:
              throw new XMPexception(syntaxErrMsg);
          }

          iter.next();
          parseArrayExpr(iter, gpuData, arrayDimCount, args);
          return;
        }
      default:
        {
          switch (prevExprOpcode) {
            case ARRAY_REF:
              {
                if (arrayDimCount != 0)
                  throw new XMPexception(syntaxErrMsg);
              } break;
            case PLUS_EXPR:
            case POINTER_REF:
              break;
            default:
              throw new XMPexception(syntaxErrMsg);
          }

          Xobject funcCall = createRewriteAlignedArrayFunc(gpuData, arrayDimCount, args, prevExprOpcode);
          iter.setPrevXobject(funcCall);
          return;
        }
    }
  }

  private static Xobject createRewriteAlignedArrayFunc(XMPgpuData gpuData, int arrayDimCount,
                                                       XobjList getAddrFuncArgs, Xcode opcode) throws XMPexception {
    XMPalignedArray alignedArray = gpuData.getXMPalignedArray();
    int arrayDim = alignedArray.getDim();
    XobjList accIdList = _accIdHash.get(alignedArray.getName());
    Ident getAddrFuncId = null;
    if (arrayDim == arrayDimCount) {
      getAddrFuncId = XMP.getMacroId("_XMP_M_GET_ADDR_E_" + arrayDim, Xtype.Pointer(alignedArray.getType()));
      for (int i = 0; i < arrayDim - 1; i++) {
        getAddrFuncArgs.add(((Ident)(accIdList.getArg(i))).Ref());
      }
    } else {
      getAddrFuncId = XMP.getMacroId("_XMP_M_GET_ADDR_" + arrayDimCount, Xtype.Pointer(alignedArray.getType()));
      for (int i = 0; i < arrayDimCount; i++) {
        getAddrFuncArgs.add(((Ident)(accIdList.getArg(i))).Ref());
      }
    }

    Xobject retObj = getAddrFuncId.Call(getAddrFuncArgs);
    switch (opcode) {
      case ARRAY_REF:
      case PLUS_EXPR:
        return retObj;
      case POINTER_REF:
        if (arrayDim == arrayDimCount) {
          return Xcons.List(Xcode.POINTER_REF, retObj.Type(), retObj);
        } else {
          return retObj;
        }
      default:
        throw new XMPexception("unknown operation in createRewriteAlignedArrayFunc()");
    }
  }

  private static Xobject getCalcIndexFuncRef(XMPgpuData gpuData, int index, Xobject indexRef) throws XMPexception {
    XMPalignedArray alignedArray = gpuData.getXMPalignedArray();
    XobjList gtolIdList = _gtolIdHash.get(alignedArray.getName());

    switch (alignedArray.getAlignMannerAt(index)) {
      case XMPalignedArray.NOT_ALIGNED:
      case XMPalignedArray.DUPLICATION:
        return indexRef;
      case XMPalignedArray.BLOCK:
        if (alignedArray.hasShadow()) {
          XMPshadow shadow = alignedArray.getShadowAt(index);
          switch (shadow.getType()) {
            case XMPshadow.SHADOW_NONE:
            case XMPshadow.SHADOW_NORMAL:
              {
                XobjList args = Xcons.List(indexRef, ((Ident)(gtolIdList.getArg(index))).Ref());
                return XMP.getMacroId("_XMP_M_CALC_INDEX_BLOCK").Call(args);
              }
            case XMPshadow.SHADOW_FULL:
              return indexRef;
            default:
              throw new XMPexception("unknown shadow type");
          }
        } else {
          XobjList args = Xcons.List(indexRef, ((Ident)(gtolIdList.getArg(index))).Ref());
          return XMP.getMacroId("_XMP_M_CALC_INDEX_BLOCK").Call(args);
        }
      case XMPalignedArray.CYCLIC:
        if (alignedArray.hasShadow()) {
          XMPshadow shadow = alignedArray.getShadowAt(index);
          switch (shadow.getType()) {
            case XMPshadow.SHADOW_NONE:
              {
                XobjList args = Xcons.List(indexRef, ((Ident)(gtolIdList.getArg(index))).Ref());
                return XMP.getMacroId("_XMP_M_CALC_INDEX_CYCLIC").Call(args);
              }
            case XMPshadow.SHADOW_FULL:
              return indexRef;
            case XMPshadow.SHADOW_NORMAL:
              throw new XMPexception("only block distribution allows shadow");
            default:
              throw new XMPexception("unknown shadow type");
          }
        } else {
          XobjList args = Xcons.List(indexRef, ((Ident)(gtolIdList.getArg(index))).Ref());
          return XMP.getMacroId("_XMP_M_CALC_INDEX_CYCLIC").Call(args);
        }
      default:
        throw new XMPexception("unknown align manner for array '" + alignedArray.getName()  + "'");
    }
  }

  // FIXME only for one dimension
/*
  private static void parseArrayExpr(bottomupXobjectIterator iter, BlockList bl, XMPgenSym symGen,
                                     CforBlock loopBlock) throws XMPexception {
    Xobject myExpr = iter.getXobject();
    switch (myExpr.Opcode()) {
      case ARRAY_REF:
        iter.next();
        parseArrayExpr(iter, bl, symGen, loopBlock);
        return;
      case PLUS_EXPR:
        {
          String varName = null;
          Xobject indexRef = null;
          Xobject left = myExpr.left();
          if (left.Opcode() == Xcode.ARRAY_REF) {
            varName = myExpr.left().getString();
            indexRef = myExpr.right();
          } else {
            Xobject right = myExpr.right();
            if (right.Opcode() == Xcode.ARRAY_REF) {
              varName = myExpr.right().getString();
              indexRef = myExpr.left();
            } else {
              throw new XMPexception("wrong array expr");
            }
          }

          HashMap<String, Ident> gpuIndexTable = (HashMap<String, Ident>)loopBlock.getProp(GPU_INDEX_TABLE);
          if (gpuIndexTable == null) {
            gpuIndexTable = new HashMap<String, Ident>();
            loopBlock.setProp(GPU_INDEX_TABLE, (Object)gpuIndexTable);
          }

          //Ident indexId = gpuIndexTable.get(alignedArray.getName());
          Ident indexId = gpuIndexTable.get("XMP_DUMMY");
          if (indexId == null) {
            indexId = Ident.Local(symGen.getStr("_XMP_gpu_idx_"), Xtype.unsignedlonglongType);
            bl.getIdentList().add(indexId);
            bl.getDecls().add(Xcons.List(Xcode.VAR_DECL, indexId, null, null));

            //gpuIndexTable.put(alignedArray.getName(), indexId);
            gpuIndexTable.put("XMP_DUMMY", indexId);

            XMPgpuData gpuData = XMPgpuDataTable.findXMPgpuData(varName, loopBlock);
            bl.add(createFuncCallBlock("_XMP_gpu_calc_index",
                                       Xcons.List(indexId.getAddr(), indexRef, gpuData.getDeviceDescId().Ref())));
          }

          left = myExpr.left();
          if (left.Opcode() == Xcode.ARRAY_REF) {
            myExpr.setRight(indexId.Ref());
          } else {
            Xobject right = myExpr.right();
            if (right.Opcode() == Xcode.ARRAY_REF) {
              myExpr.setLeft(indexId.Ref());
            } else {
              throw new XMPexception("wrong array expr");
            }
          }

          iter.setXobject(myExpr);
        } return;
      default:
        throw new XMPexception("wrong array expr");
    }
  }
*/
}
