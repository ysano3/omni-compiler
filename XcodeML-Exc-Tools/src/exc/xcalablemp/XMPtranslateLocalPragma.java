package exc.xcalablemp;

import exc.block.*;
import exc.object.*;
import java.util.Vector;
import java.util.Iterator;

public class XMPtranslateLocalPragma {
  private XMPglobalDecl		_globalDecl;
  private XobjectFile		_env;
  private XMPobjectTable	_globalObjectTable;

  public XMPtranslateLocalPragma(XMPglobalDecl globalDecl) {
    _globalDecl = globalDecl;
    _env = globalDecl.getEnv();
    _globalObjectTable = globalDecl.getGlobalObjectTable();
  }

  public void translate(FuncDefBlock def) throws XMPexception {
    FunctionBlock fb = def.getBlock();

    BlockIterator i = new topdownBlockIterator(fb);
    for (i.init(); !i.end(); i.next()) {
      Block b = i.getBlock();
      if (b.Opcode() ==  Xcode.XMP_PRAGMA) translatePragma((PragmaBlock)b);
    }

    def.Finalize();
  }

  private void translatePragma(PragmaBlock pb) throws XMPexception {
    String pragmaName = pb.getPragma();

    switch (XMPpragma.valueOf(pragmaName)) {
      case NODES:
        { translateNodes(pb);		break; }
      case TEMPLATE:
        { translateTemplate(pb);	break; }
      case DISTRIBUTE:
        { translateDistribute(pb);	break; }
      case ALIGN:
        { translateAlign(pb);		break; }
      case SHADOW:
        { translateShadow(pb);		break; }
      case TASK:
        { translateTask(pb);		break; }
      case TASKS:
        { translateTasks(pb);		break; }
      case LOOP:
        { translateLoop(pb);		break; }
      case REFLECT:
        { translateReflect(pb);		break; }
      case BARRIER:
        { translateBarrier(pb);		break; }
      case REDUCTION:
        { translateReduction(pb);	break; }
      case BCAST:
        { translateBcast(pb);		break; }
      default:
        XMP.fatal("'" + pragmaName.toLowerCase() + "' directive is not supported yet");
    }
  }

  private void translateNodes(PragmaBlock pb) throws XMPexception {
    LineNo lnObj = pb.getLineNo();

    // check location
    checkDeclPragmaLocation(pb);

    // start translation
    XobjList nodesDecl = (XobjList)pb.getClauses();
    FunctionBlock functionBlock = XMPlocalDecl.findParentFunctionBlock(pb);
    BlockList funcBlockList = functionBlock.getBody();
    XMPobjectTable localObjectTable = XMPlocalDecl.declObjectTable(pb);

    // check <map-type> := { <undefined> | regular }
    int nodesMapType = 0;
    if (nodesDecl.getArg(0) == null) nodesMapType = XMPnodes.MAP_UNDEFINED;
    else nodesMapType = XMPnodes.MAP_REGULAR;

    // check name collision
    String nodesName = nodesDecl.getArg(1).getString();
    checkObjectNameCollision(lnObj, nodesName, funcBlockList, localObjectTable);

    // declare nodes desciptor
    Ident nodesDescId = XMPlocalDecl.addObjectId(XMP.DESC_PREFIX_ + nodesName, pb);

    // declare nodes object
    int nodesDim = 0;
    for (XobjArgs i = nodesDecl.getArg(2).getArgs(); i != null; i = i.nextArgs()) nodesDim++;
    if ((nodesDim > (XMP.MAX_DIM)) || (nodesDim < 1))
      XMP.error(lnObj, "nodes dimension should be less than " + (XMP.MAX_DIM + 1));

    XMPnodes nodesObject = new XMPnodes(lnObj.lineNo(), nodesName, nodesDim, nodesDescId);
    localObjectTable.putObject(nodesObject);

    // create function call
    XobjList nodesArgs = Xcons.List(Xcons.IntConstant(nodesMapType), nodesDescId.getAddr(), Xcons.IntConstant(nodesDim));

    XobjList inheritDecl = (XobjList)nodesDecl.getArg(3);
    String inheritType = null;
    String nodesRefType = null;
    XobjList nodesRef = null;
    XMPnodes nodesRefObject = null;
    switch (inheritDecl.getArg(0).getInt()) {
      case XMPnodes.INHERIT_GLOBAL:
        inheritType = "GLOBAL";
        break;
      case XMPnodes.INHERIT_EXEC:
        inheritType = "EXEC";
        break;
      case XMPnodes.INHERIT_NODES:
        {
          inheritType = "NODES";

          nodesRef = (XobjList)inheritDecl.getArg(1);
          if (nodesRef.getArg(0) == null) {
            nodesRefType = "NUMBER";

            XobjList nodeNumberTriplet = (XobjList)nodesRef.getArg(1);
            // lower
            if (nodeNumberTriplet.getArg(0) == null) nodesArgs.add(Xcons.IntConstant(1));
            else nodesArgs.add(nodeNumberTriplet.getArg(0));
            // upper
            if (nodeNumberTriplet.getArg(1) == null) nodesArgs.add(_globalDecl.getWorldSizeId().Ref());
            else nodesArgs.add(nodeNumberTriplet.getArg(1));
            // stride
            if (nodeNumberTriplet.getArg(2) == null) nodesArgs.add(Xcons.IntConstant(1));
            else nodesArgs.add(nodeNumberTriplet.getArg(2));
          }
          else {
            nodesRefType = "NAMED";

            String nodesRefName = nodesRef.getArg(0).getString();
            nodesRefObject = findXMPnodes(lnObj, nodesRefName, localObjectTable);
            nodesArgs.add(nodesRefObject.getDescId().Ref());

            int nodesRefDim = nodesRefObject.getDim();
            boolean isDynamicNodesRef = false;
            XobjList subscriptList = (XobjList)nodesRef.getArg(1);
            if (subscriptList == null) {
              for (int nodesRefIndex = 0; nodesRefIndex < nodesRefDim; nodesRefIndex++) {
                // lower
                nodesArgs.add(Xcons.Cast(Xtype.intType, Xcons.IntConstant(1)));
                // upper
                Xobject nodesRefSize = nodesRefObject.getUpperAt(nodesRefIndex);
                if (nodesRefSize == null) isDynamicNodesRef = true;
                else nodesArgs.add(Xcons.Cast(Xtype.intType, nodesRefSize));
                // stride
                nodesArgs.add(Xcons.Cast(Xtype.intType, Xcons.IntConstant(1)));
              }
            }
            else {
              int nodesRefIndex = 0;
              for (XobjArgs i = subscriptList.getArgs(); i != null; i = i.nextArgs()) {
                if (nodesRefIndex == nodesRefDim)
                  XMP.error(lnObj, "wrong nodes dimension indicated, too many");

                XobjList subscriptTriplet = (XobjList)i.getArg();
                // lower
                if (subscriptTriplet.getArg(0) == null) nodesArgs.add(Xcons.Cast(Xtype.intType, Xcons.IntConstant(1)));
                else nodesArgs.add(Xcons.Cast(Xtype.intType, subscriptTriplet.getArg(0)));
                // upper
                if (subscriptTriplet.getArg(1) == null) {
                  Xobject nodesRefSize = nodesRefObject.getUpperAt(nodesRefIndex);
                  if (nodesRefSize == null) isDynamicNodesRef = true;
                  else nodesArgs.add(Xcons.Cast(Xtype.intType, nodesRefSize));
                }
                else nodesArgs.add(Xcons.Cast(Xtype.intType, subscriptTriplet.getArg(1)));
                // stride
                if (subscriptTriplet.getArg(2) == null) nodesArgs.add(Xcons.Cast(Xtype.intType, Xcons.IntConstant(1)));
                else nodesArgs.add(Xcons.Cast(Xtype.intType, subscriptTriplet.getArg(2)));

                nodesRefIndex++;
              }

              if (nodesRefIndex != nodesRefDim)
                XMP.error(lnObj, "the number of <nodes-subscript> should be the same with the nodes dimension");
            }

            if (isDynamicNodesRef) nodesArgs.cons(Xcons.IntConstant(1));
            else                   nodesArgs.cons(Xcons.IntConstant(0));
          }
          break;
        }
      default:
        XMP.fatal("cannot create sub node set, unknown operation in nodes directive");
    }

    boolean isDynamic = false;
    for (XobjArgs i = nodesDecl.getArg(2).getArgs(); i != null; i = i.nextArgs()) {
      Xobject nodesSize = i.getArg();
      if (nodesSize == null) isDynamic = true;
      else nodesArgs.add(Xcons.Cast(Xtype.intType, nodesSize));

      nodesObject.addUpper(nodesSize);
    }

    String allocType = null;
    if (isDynamic)      allocType = "DYNAMIC";
    else                allocType = "STATIC";

    // add constructor call
    if (nodesRef == null)
      XMPlocalDecl.addConstructorCall("_XCALABLEMP_init_nodes_" + allocType + "_" + inheritType, nodesArgs, pb, _globalDecl);
    else
      XMPlocalDecl.addConstructorCall("_XCALABLEMP_init_nodes_" + allocType + "_" + inheritType + "_" + nodesRefType,
                                      nodesArgs, pb, _globalDecl);

    // insert destructor call
    XMPlocalDecl.insertDestructorCall("_XCALABLEMP_finalize_nodes", Xcons.List(nodesDescId.Ref()), pb, _globalDecl);
  }

  private void translateTemplate(PragmaBlock pb) throws XMPexception {
    LineNo lnObj = pb.getLineNo();

    // check location
    checkDeclPragmaLocation(pb);

    // start translation
    XobjList templateDecl = (XobjList)pb.getClauses();
    FunctionBlock functionBlock = XMPlocalDecl.findParentFunctionBlock(pb);
    BlockList funcBlockList = functionBlock.getBody();
    XMPobjectTable localObjectTable = XMPlocalDecl.declObjectTable(pb);

    // check name collision - parameters
    String templateName = templateDecl.getArg(0).getString();
    checkObjectNameCollision(lnObj, templateName, funcBlockList, localObjectTable);

    // declare template desciptor
    Ident templateDescId = XMPlocalDecl.addObjectId(XMP.DESC_PREFIX_ + templateName, pb);

    // declare template object
    int templateDim = 0;
    for (XobjArgs i = templateDecl.getArg(1).getArgs(); i != null; i = i.nextArgs()) templateDim++;
    if ((templateDim > (XMP.MAX_DIM)) || (templateDim < 1))
      XMP.error(lnObj, "template dimension should be less than " + (XMP.MAX_DIM + 1));

    XMPtemplate templateObject = new XMPtemplate(lnObj.lineNo(), templateName, templateDim, templateDescId);
    localObjectTable.putObject(templateObject);

    // create function call
    boolean templateIsFixed = true;
    XobjList templateArgs = Xcons.List(templateDescId.getAddr(), Xcons.IntConstant(templateDim));
    for (XobjArgs i = templateDecl.getArg(1).getArgs(); i != null; i = i.nextArgs()) {
      Xobject templateSpec = i.getArg();
      if (templateSpec == null) {
        templateIsFixed = false;

        templateObject.addLower(null);
        templateObject.addUpper(null);
      }
      else {
        Xobject templateLower = templateSpec.left();
        Xobject templateUpper = templateSpec.right();

        templateArgs.add(Xcons.Cast(Xtype.longlongType, templateLower));
        templateArgs.add(Xcons.Cast(Xtype.longlongType, templateUpper));

        templateObject.addLower(templateLower);
        templateObject.addUpper(templateUpper);
      }
    }

    String fixedSurfix = null;
    if (templateIsFixed) {
      templateObject.setIsFixed();
      fixedSurfix = "FIXED";
    }
    else fixedSurfix = "UNFIXED";

    // add constructor call
    XMPlocalDecl.addConstructorCall("_XCALABLEMP_init_template_" + fixedSurfix, templateArgs, pb, _globalDecl);

    // insert destructor call
    XMPlocalDecl.insertDestructorCall("_XCALABLEMP_finalize_template", Xcons.List(templateDescId.Ref()), pb, _globalDecl);
  }

  private void checkObjectNameCollision(LineNo lnObj, String name,
                                        BlockList scopeBL, XMPobjectTable objectTable) throws XMPexception {
    // check name collision - parameters
    if (scopeBL.findLocalIdent(name) != null)
      XMP.error(lnObj, "'" + name + "' is already declared");

    // check name collision - local object table
    if (objectTable.getObject(name) != null) {
      int ln = _globalObjectTable.getObject(name).getLineNo();
      XMP.error(lnObj, "'" + name + "' is already declared in line." + ln);
    }

    // check name collision - descriptor name
    if (scopeBL.findLocalIdent(XMP.DESC_PREFIX_ + name) != null) {
      // FIXME generate unique name
      XMP.error(lnObj, "cannot declare template desciptor, '" + XMP.DESC_PREFIX_ + name + "' is already declared");
    }
  }

  private void translateDistribute(PragmaBlock pb) throws XMPexception {
    LineNo lnObj = pb.getLineNo();

    // check location
    checkDeclPragmaLocation(pb);

    // start translation
    XobjList distDecl = (XobjList)pb.getClauses();
    FunctionBlock functionBlock = XMPlocalDecl.findParentFunctionBlock(pb);
    BlockList funcBlockList = functionBlock.getBody();
    XMPobjectTable localObjectTable = XMPlocalDecl.declObjectTable(pb);

    // get template object
    String templateName = distDecl.getArg(0).getString();
    XMPtemplate templateObject = localObjectTable.getTemplate(templateName);
    if (templateObject == null) {
      templateObject = _globalObjectTable.getTemplate(templateName);
      if (templateObject == null)
        XMP.error(lnObj, "template '" + templateName + "' is not declared");
      else
        XMP.error(lnObj, "global template cannot be distributed in local scope");
    }

    if (templateObject.isDistributed())
      XMP.error(lnObj, "template '" + templateName + "' is already distributed");

    if (!templateObject.isFixed())
      XMP.error(lnObj, "the size of template '" + templateName + "' is not fixed");

    // get nodes object
    String nodesName = distDecl.getArg(2).getString();
    XMPnodes nodesObject = findXMPnodes(lnObj, nodesName, localObjectTable);

    templateObject.setOntoNodes(nodesObject);

    // setup chunk constructor & destructor
    XMPlocalDecl.addConstructorCall("_XCALABLEMP_init_template_chunk",
                                    Xcons.List(templateObject.getDescId().Ref(),
                                               nodesObject.getDescId().Ref()),
                                    pb, _globalDecl);

    // create distribute function calls
    int templateDim = templateObject.getDim();
    int templateDimIdx = 0;
    int nodesDim = nodesObject.getDim();
    int nodesDimIdx = 0;
    for (XobjArgs i = distDecl.getArg(1).getArgs(); i != null; i = i.nextArgs()) {
      if (templateDimIdx == templateDim)
        XMP.error(lnObj, "wrong template dimension indicated, too many");

      int distManner = i.getArg().getInt();
      // FIXME support cyclic(w), gblock
      switch (distManner) {
        case XMPtemplate.DUPLICATION:
        case XMPtemplate.BLOCK:
        case XMPtemplate.CYCLIC:
          if (nodesDimIdx == nodesDim)
            XMP.error(lnObj, "the number of <dist-format> (except '*') should be the same with the nodes dimension");

          setupDistribution(distManner, pb, templateObject, templateDimIdx, nodesObject, nodesDimIdx);
          nodesDimIdx++;
          break;
        default:
          XMP.fatal("unknown distribute manner");
      }

      templateDimIdx++;
    }

    // check nodes, template dimension
    if (nodesDimIdx != nodesDim)
      XMP.error(lnObj, "the number of <dist-format> (except '*') should be the same with the nodes dimension");

    if (templateDimIdx != templateDim)
      XMP.error(lnObj, "wrong template dimension indicated, too few");

    // set distributed
    templateObject.setIsDistributed();
  }

  private void setupDistribution(int distManner, PragmaBlock pb,
                                 XMPtemplate templateObject, int templateDimIdx,
                                 XMPnodes nodesObject,       int nodesDimIdx) {
    String distMannerName = null;
    switch (distManner) {
      case XMPtemplate.DUPLICATION:
        distMannerName = "DUPLICATION";
        break;
      case XMPtemplate.BLOCK:
        distMannerName = "BLOCK";
        break;
      case XMPtemplate.CYCLIC:
        distMannerName = "CYCLIC";
        break;
      default:
        XMP.fatal("unknown distribute manner");
    }

    XMPlocalDecl.addConstructorCall("_XCALABLEMP_dist_template_" + distMannerName,
                                    Xcons.List(templateObject.getDescId().Ref(), Xcons.IntConstant(templateDimIdx),
                                               nodesObject.getDescId().Ref(), Xcons.IntConstant(nodesDimIdx)),
                                    pb, _globalDecl);
    templateObject.setOntoNodesIndexAt(nodesDimIdx, templateDimIdx);
    templateObject.setDistMannerAt(distManner, templateDimIdx);
  }

  private void translateAlign(PragmaBlock pb) throws XMPexception {
    LineNo lnObj = pb.getLineNo();

    // check location
    checkDeclPragmaLocation(pb);

    // start translation
    XobjList alignDecl = (XobjList)pb.getClauses();
    FunctionBlock functionBlock = XMPlocalDecl.findParentFunctionBlock(pb);
    BlockList funcBlockList = functionBlock.getBody();
    XMPobjectTable localObjectTable = XMPlocalDecl.declObjectTable(pb);

    // get array information
    String arrayName = alignDecl.getArg(0).getString();
    if (localObjectTable.getAlignedArray(arrayName) != null)
      XMP.error(lnObj, "array '" + arrayName + "' is already aligned");

    Ident arrayId = funcBlockList.findLocalIdent(arrayName);
    if (arrayId == null)
      XMP.error(lnObj, "array '" + arrayName + "' is not declared");

    if (arrayId.getStorageClass() != StorageClass.PARAM)
      XMP.error(lnObj, "array '" + arrayName + "' is not a parameter");

    Xtype arrayType = arrayId.Type();
    if (arrayType.getKind() != Xtype.ARRAY)
      XMP.error(lnObj, arrayName + " is not an array");

    Xtype arrayElmtType = arrayType.getArrayElementType();

    // get template information
    String templateName = alignDecl.getArg(2).getString();
    XMPtemplate templateObj = findXMPtemplate(lnObj, templateName, localObjectTable);

    if (!(templateObj.isDistributed()))
      XMP.error(lnObj, "template '" + templateName + "' is not distributed");

    int templateDim = templateObj.getDim();

    // declare array address pointer
    Ident arrayAddrId = XMPlocalDecl.addObjectId(XMP.ADDR_PREFIX_ + arrayName, Xtype.Pointer(arrayElmtType), pb);

    // declare array descriptor
    Ident arrayDescId = XMPlocalDecl.addObjectId(XMP.DESC_PREFIX_ + arrayName, pb);

    int arrayDim = arrayType.getNumDimensions();
    if ((arrayDim > (XMP.MAX_DIM)) || (arrayDim < 1))
      XMP.error(lnObj, "array dimension should be less than " + (XMP.MAX_DIM + 1));

    XobjList initArrayDescFuncArgs = Xcons.List(arrayDescId.getAddr(),
                                                templateObj.getDescId().Ref(),
                                                Xcons.IntConstant(arrayDim));

    Vector<Long> arraySizeVector = new Vector<Long>(arrayDim);
    Vector<Ident> gtolAccIdVector = new Vector<Ident>(arrayDim);
    for (int i = 0; i < arrayDim; i++, arrayType = arrayType.getRef()) {
      long dimSize = arrayType.getArraySize();
      if(dimSize == 0)
        XMP.error(lnObj, "array size cannot be omitted");

      arraySizeVector.add(new Long(dimSize));
      initArrayDescFuncArgs.add(Xcons.Cast(Xtype.intType, Xcons.LongLongConstant(0, dimSize)));

      Ident gtolAccId = XMPlocalDecl.addObjectId(XMP.GTOL_PREFIX_ + "acc_" + arrayName + "_" + i,
                                                 Xtype.unsignedlonglongType, pb);
      gtolAccIdVector.add(gtolAccId);
    }

    // create/destroy local descriptor
    XMPlocalDecl.addConstructorCall("_XCALABLEMP_init_array_desc", initArrayDescFuncArgs, pb, _globalDecl);
    XMPlocalDecl.insertDestructorCall("_XCALABLEMP_finalize_array_desc", Xcons.List(arrayDescId.Ref()), pb, _globalDecl);

    XMPalignedArray alignedArray = new XMPalignedArray(lnObj, arrayName, arrayElmtType, arrayDim,
                                                       arraySizeVector, gtolAccIdVector, arrayDescId, arrayAddrId);
    localObjectTable.putAlignedArray(alignedArray);

    // check <align-source> list, <align-subscrip> list
    XobjList alignSourceList = (XobjList)alignDecl.getArg(1);
    XobjList alignSubscriptList = (XobjList)alignDecl.getArg(3);
    XobjList alignSubscriptVarList = (XobjList)alignSubscriptList.left();
    XobjList alignSubscriptExprList = (XobjList)alignSubscriptList.right();

    // check <align-source> list
    if (XMPutil.countElmts(alignSourceList) != arrayDim)
      XMP.error(lnObj, "the number of <align-source>s is not the same with array dimension");

    // check <align-subscript> list
    if (XMPutil.countElmts(alignSubscriptVarList) != templateDim)
      XMP.error(lnObj, "the number of <align-subscript>s is not the same with template dimension");

    // check ':' source/subscript
    if (XMPutil.countElmts(alignSourceList, XMP.COLON) !=
        XMPutil.countElmts(alignSubscriptVarList, XMP.COLON))
      XMP.error(lnObj, "the number of ':' in <align-source> list is not the same with <align-subscript> list");

    // create align function calls
    int alignSourceIndex = 0;
    for (XobjArgs i = alignSourceList.getArgs(); i != null; i = i.nextArgs()) {
      Xobject alignSourceObj = i.getArg();
      if (alignSourceObj.Opcode() == Xcode.INT_CONSTANT) {
        int alignSource = alignSourceObj.getInt();
        if (alignSource == XMPalignedArray.NO_ALIGN) continue;
        else if (alignSource == XMPalignedArray.SIMPLE_ALIGN) {
          if (!XMPutil.hasElmt(alignSubscriptVarList, XMPalignedArray.SIMPLE_ALIGN))
            XMP.error(lnObj, "cannot find ':' in <align-subscript> list");

          int alignSubscriptIndex = XMPutil.getLastIndex(alignSubscriptVarList, XMPalignedArray.SIMPLE_ALIGN);
          alignSubscriptVarList.setArg(alignSubscriptIndex, null);

          declAlignFunc(alignedArray, alignSourceIndex, templateObj, alignSubscriptIndex, null, pb);
        }
      }
      else if (alignSourceObj.Opcode() == Xcode.STRING) {
        String alignSource = alignSourceObj.getString();
        if (XMPutil.countElmts(alignSourceList, alignSource) != 1)
          XMP.error(lnObj, "multiple '" + alignSource + "' indicated in <align-source> list");

        if (!XMPutil.hasElmt(alignSubscriptVarList, alignSource)) continue;

        if (XMPutil.countElmts(alignSubscriptVarList, alignSource) != 1)
          XMP.error(lnObj, "multiple '" + alignSource + "' indicated in <align-subscript> list");

        int alignSubscriptIndex = XMPutil.getFirstIndex(alignSubscriptVarList, alignSource);
        declAlignFunc(alignedArray, alignSourceIndex, templateObj, alignSubscriptIndex,
                      alignSubscriptExprList.getArg(alignSubscriptIndex), pb);
      }

      alignSourceIndex++;
    }

    // init array address
    XobjList initArrayAddrFuncArgs = Xcons.List(arrayAddrId.getAddr(),
                                                arrayId.getAddr(),
                                                arrayDescId.Ref());
    for (int i = arrayDim - 1; i >= 0; i--)
      initArrayAddrFuncArgs.add(Xcons.Cast(Xtype.unsignedlonglongType,
                                           alignedArray.getGtolAccIdAt(i).getAddr()));

    XMPlocalDecl.addConstructorCall("_XCALABLEMP_init_array_addr", initArrayAddrFuncArgs, pb, _globalDecl);
  }

  private void declAlignFunc(XMPalignedArray alignedArray, int alignSourceIndex,
                             XMPtemplate templateObj, int alignSubscriptIndex,
                             Xobject alignSubscriptExpr, PragmaBlock pb) throws XMPexception {
    XobjList alignFuncArgs = Xcons.List(alignedArray.getDescId().Ref(),
                                        Xcons.IntConstant(alignSourceIndex),
                                        templateObj.getDescId().Ref(),
                                        Xcons.IntConstant(alignSubscriptIndex));

    if (alignSubscriptExpr == null) alignFuncArgs.add(Xcons.IntConstant(0));
    else alignFuncArgs.add(alignSubscriptExpr);

    int distManner = templateObj.getDistMannerAt(alignSubscriptIndex);
    alignedArray.setDistMannerAt(distManner, alignSourceIndex);

    switch (distManner) {
      case XMPtemplate.BLOCK:
      case XMPtemplate.CYCLIC:
        {
          Ident gtolTemp0Id = XMPlocalDecl.addObjectId(XMP.GTOL_PREFIX_ + "temp0_" + alignedArray.getName() + "_" + alignSourceIndex,
                                                       Xtype.intType, pb);
          alignedArray.setGtolTemp0IdAt(gtolTemp0Id, alignSourceIndex);
          alignFuncArgs.add(gtolTemp0Id.getAddr());

          break;
        }
      default:
        XMP.fatal("unknown distribute manner");
    }

    XMPlocalDecl.addConstructorCall("_XCALABLEMP_align_array_" + templateObj.getDistMannerStringAt(alignSubscriptIndex),
                                    alignFuncArgs, pb, _globalDecl);
  }

  // FIXME incomplete, not checked
  private void translateShadow(PragmaBlock pb) throws XMPexception {
    LineNo lnObj = pb.getLineNo();

    // check position
    checkDeclPragmaLocation(pb);

    // start translation
    XobjList shadowDecl = (XobjList)pb.getClauses();
    XMPobjectTable localObjectTable = XMPlocalDecl.declObjectTable(pb);

    // find aligned array
    String arrayName = shadowDecl.getArg(0).getString();
    XMPalignedArray alignedArray = localObjectTable.getAlignedArray(arrayName);
    if (alignedArray == null)
      XMP.error(lnObj, "the aligned array '" + arrayName + "' is not found in local scope");

    if (alignedArray.hasShadow())
      XMP.error(lnObj, "the aligned array '" + arrayName + "' has shadow already");

    // init shadow
    XobjList shadowFuncArgs = Xcons.List(alignedArray.getDescId().Ref());
    int arrayIndex = 0;
    int arrayDim = alignedArray.getDim();
    for (XobjArgs i = shadowDecl.getArg(1).getArgs(); i != null; i = i.nextArgs()) {
      if (arrayIndex == arrayDim)
        XMP.error(lnObj, "wrong shadow dimension indicated, too many");

      XobjList shadowObj = (XobjList)i.getArg();
      if (shadowObj == null) {
        shadowFuncArgs.add(Xcons.Cast(Xtype.intType, Xcons.IntConstant(XMPshadow.SHADOW_FULL)));

        alignedArray.setShadowAt(new XMPshadow(XMPshadow.SHADOW_FULL, null, null), arrayIndex);
      }
      else {
        shadowFuncArgs.add(Xcons.Cast(Xtype.intType, Xcons.IntConstant(XMPshadow.SHADOW_NORMAL)));
        shadowFuncArgs.add(Xcons.Cast(Xtype.intType, shadowObj.left()));
        shadowFuncArgs.add(Xcons.Cast(Xtype.intType, shadowObj.right()));

        alignedArray.setShadowAt(new XMPshadow(XMPshadow.SHADOW_NORMAL, shadowObj.left(), shadowObj.right()), arrayIndex);
      }

      arrayIndex++;
    }

    if (arrayIndex != arrayDim)
      XMP.error(lnObj, "the number of <nodes/template-subscript> should be the same with the dimension");

    XMPlocalDecl.addConstructorCall("_XCALABLEMP_init_shadow", shadowFuncArgs, pb, _globalDecl);

    // set shadow flag
    alignedArray.setHasShadow();
  }

  private void translateTask(PragmaBlock pb) throws XMPexception {
    LineNo lnObj = pb.getLineNo();

    // start translation
    XobjList taskDecl = (XobjList)pb.getClauses();
    XMPobjectTable localObjectTable = XMPlocalDecl.declObjectTable(pb);
    BlockList taskBody = pb.getBody();

    // create function arguments
    XobjList onRef = (XobjList)taskDecl.getArg(0);
    XMPtriplet<String, Boolean, XobjList> execOnRefArgs = createExecOnRefArgs(lnObj, onRef, localObjectTable);
    String execFuncSurfix = execOnRefArgs.getFirst();
    boolean splitComm = execOnRefArgs.getSecond().booleanValue();
    XobjList execFuncArgs = execOnRefArgs.getThird();

    // setup task finalizer
    Ident finFuncId = null;
    if (splitComm) finFuncId = _globalDecl.declExternFunc("_XCALABLEMP_pop_n_free_nodes");
    else           finFuncId = _globalDecl.declExternFunc("_XCALABLEMP_pop_nodes");
    setupFinalizer(lnObj, taskBody, finFuncId, null);

    // create function call
    Ident execFuncId = _env.declExternIdent("_XCALABLEMP_exec_task_" + execFuncSurfix, Xtype.Function(Xtype.boolType));
    pb.replace(Bcons.IF(BasicBlock.Cond(execFuncId.Call(execFuncArgs)), taskBody, null));
  }

  private void translateTasks(PragmaBlock pb) {
    LineNo lnObj = pb.getLineNo();
    System.out.println("TASKS:" + pb.toXobject().toString());
  }

  private void translateLoop(PragmaBlock pb) throws XMPexception {
    XobjList loopDecl = (XobjList)pb.getClauses();
    BlockList loopBody = pb.getBody();

    // replace pragma
    CforBlock schedBaseBlock = getOutermostLoopBlock(pb.getLineNo(), loopBody);
    pb.replace(schedBaseBlock);

    if (loopDecl.getArg(0) == null) translateFollowingLoop(pb, schedBaseBlock);
    else                            translateMultipleLoop(pb, schedBaseBlock);
  }

  private void translateFollowingLoop(PragmaBlock pb, CforBlock schedBaseBlock) throws XMPexception {
    LineNo lnObj = pb.getLineNo();
    XobjList loopDecl = (XobjList)pb.getClauses();

    // schedule loop
    XobjInt loopIndex = scheduleLoop(pb, schedBaseBlock, schedBaseBlock);

    // create reduce functions
    if (loopDecl.getArg(2) != null) {
      // create on-ref for reduction
      XobjList originalOnRef = (XobjList)loopDecl.getArg(1);
      XobjList reductionOnRef = Xcons.List(originalOnRef.getArg(0), null);
    }
  }

  private void translateMultipleLoop(PragmaBlock pb, CforBlock schedBaseBlock) throws XMPexception {
    LineNo lnObj = pb.getLineNo();

    // start translation
    XobjList loopDecl = (XobjList)pb.getClauses();
    BlockList loopBody = pb.getBody();

    // iterate index variable list
    XobjList loopVarList = (XobjList)loopDecl.getArg(0);
    Vector<CforBlock> loopVector = new Vector<CforBlock>(XMPutil.countElmts(loopVarList));
    for (XobjArgs i = loopVarList.getArgs(); i != null; i = i.nextArgs())
      loopVector.add(findLoopBlock(lnObj, loopBody, i.getArg().getString()));

    // schedule loop
    Vector<XobjInt> loopIndexVector = new Vector<XobjInt>(loopVector.size());
    Iterator<CforBlock> it = loopVector.iterator();
    while (it.hasNext()) {
      CforBlock forBlock = it.next();
      XobjInt loopIndex = scheduleLoop(pb, forBlock, schedBaseBlock);
      loopIndexVector.add(loopIndex);
    }

    // create reduce functions
    if (loopDecl.getArg(2) != null) {
      // create on-ref for reduction
      XobjList originalOnRef = (XobjList)loopDecl.getArg(1);
      XobjList reductionOnRef = Xcons.List(originalOnRef.getArg(0), null);
    }
  }

  private XobjInt scheduleLoop(PragmaBlock pb, CforBlock forBlock, CforBlock schedBaseBlock) throws XMPexception {
    LineNo lnObj = pb.getLineNo();
    XobjList loopDecl = (XobjList)pb.getClauses();
    XMPobjectTable localObjectTable = XMPlocalDecl.declObjectTable(schedBaseBlock);

    // analyze <on-ref>
    Xobject onRef = loopDecl.getArg(1);
    String onRefObjName = onRef.getArg(0).getString();
    XMPobject onRefObj = findXMPobject(lnObj, onRefObjName, localObjectTable);
    switch (onRefObj.getKind()) {
      case XMPobject.TEMPLATE:
        {
          XMPtemplate onRefTemplate = (XMPtemplate)onRefObj;
          if (!(onRefTemplate.isDistributed()))
            XMP.error(lnObj, "template '" + onRefObjName + "' is not distributed");

          return callLoopSchedFuncTemplate(onRefTemplate, (XobjList)onRef.getArg(1), forBlock, schedBaseBlock);
        }
      case XMPobject.NODES:
        return callLoopSchedFuncNodes((XMPnodes)onRefObj, (XobjList)onRef.getArg(1), forBlock, schedBaseBlock);
      default:
        XMP.fatal("unknown object type");
        // XXX never reach here
        return null;
    }
  }

  private static CforBlock getOutermostLoopBlock(LineNo lnObj, BlockList body) throws XMPexception {
    Block b = body.getHead();
    if (b != null) {
      if (b.Opcode() == Xcode.FOR_STATEMENT) {
        CforBlock forBlock = (CforBlock)b;
        forBlock.Canonicalize();
        if (!(forBlock.isCanonical()))
          XMP.error(b.getLineNo(), "loop is not canonical");

        return forBlock;
      }
      else if (b.Opcode() == Xcode.COMPOUND_STATEMENT)
        return getOutermostLoopBlock(lnObj, b.getBody());
    }

    XMP.error(lnObj, "cannot find the loop statement");
    // never reach here
    return null;
  }

  private static CforBlock findLoopBlock(LineNo lnObj, BlockList body, String loopVarName) throws XMPexception {
    Block b = body.getHead();
    if (b != null) {
      LineNo blockLnObj = b.getLineNo();

      switch (b.Opcode()) {
        case FOR_STATEMENT:
          {
            CforBlock forBlock = (CforBlock)b;
            forBlock.Canonicalize();
            if (!(forBlock.isCanonical()))
              XMP.error(blockLnObj, "loop is not canonical");

            if (forBlock.getInductionVar().getSym().equals(loopVarName))
              return (CforBlock)b;
            else
              return findLoopBlock(blockLnObj, forBlock.getBody(), loopVarName);
          }
        case COMPOUND_STATEMENT:
          return findLoopBlock(blockLnObj, b.getBody(), loopVarName);
        case XMP_PRAGMA:
        case OMP_PRAGMA:
          XMP.error(blockLnObj, "reached to a openmp/xcalablemp directive");
      }
    }

    XMP.error(lnObj, "cannot find the loop statement");
    // never reach here
    return null;
  }

  private XobjInt callLoopSchedFuncTemplate(XMPtemplate templateObj, XobjList templateSubscriptList,
                                            CforBlock forBlock, CforBlock schedBaseBlock) throws XMPexception {
    LineNo lnObj = forBlock.getLineNo();

    Xobject loopIndex = forBlock.getInductionVar();
    Xtype loopIndexType = loopIndex.Type();

    if (!XMPutil.isIntegerType(loopIndexType))
      XMP.error(lnObj, "loop index variable has a non-integer type");

    String funcTypeSurfix = XMPutil.getTypeName(loopIndexType);
    String loopIndexName = loopIndex.getSym();

    XobjList funcArgs = Xcons.List();
    funcArgs.add(forBlock.getLowerBound());
    funcArgs.add(forBlock.getUpperBound());
    funcArgs.add(forBlock.getStep());

    int templateIndex = 0;
    int templateDim = templateObj.getDim();
    XobjInt templateIndexArg = null;
    int distManner = 0;
    String distMannerString = null;
    for (XobjArgs i = templateSubscriptList.getArgs(); i != null; i = i.nextArgs()) {
      if (templateIndex >= templateDim)
        XMP.error(lnObj, "wrong template dimensions, too many");

      String s = i.getArg().getString();
      if (s.equals(loopIndexName)) {
        if (templateIndexArg != null)
          XMP.error(lnObj, "loop index '" + loopIndexName + "' is already described");

        templateIndexArg = Xcons.IntConstant(templateIndex);
        distManner = templateObj.getDistMannerAt(templateIndex);
        if (distManner == XMPtemplate.DUPLICATION) {
          XMP.warning(lnObj, "indicated template dimension is not distributed");
          return templateIndexArg;
        }
        else distMannerString = templateObj.getDistMannerStringAt(templateIndex);
      }

      templateIndex++;
    }

    if(templateIndexArg == null)
      XMP.error(lnObj, "cannot find index '" + loopIndexName + "' reference in <on-ref>");

    if(templateIndex != templateDim)
      XMP.error(lnObj, "wrong template dimensions, too few");

    Ident parallelInitId = declLoopSchedIdent(schedBaseBlock,
                                              "_XCALABLEMP_loop_init_" + loopIndexName, loopIndexType);
    Ident parallelCondId = declLoopSchedIdent(schedBaseBlock,
                                              "_XCALABLEMP_loop_cond_" + loopIndexName, loopIndexType);
    Ident parallelStepId = declLoopSchedIdent(schedBaseBlock,
                                              "_XCALABLEMP_loop_step_" + loopIndexName, loopIndexType);

    forBlock.setLowerBound(parallelInitId.Ref());
    forBlock.setUpperBound(parallelCondId.Ref());
    forBlock.setStep(parallelStepId.Ref());

    forBlock.getCondBBlock().setExpr(Xcons.binaryOp(Xcode.LOG_LT_EXPR, loopIndex, parallelCondId.Ref()));

    funcArgs.add(parallelInitId.getAddr());
    funcArgs.add(parallelCondId.getAddr());
    funcArgs.add(parallelStepId.getAddr());

    funcArgs.add(templateObj.getDescId().Ref());
    funcArgs.add(templateIndexArg);

    Ident funcId = _globalDecl.declExternFunc("_XCALABLEMP_sched_loop_template_" + distMannerString + "_" + funcTypeSurfix);

    schedBaseBlock.insert(funcId.Call(funcArgs));

    return templateIndexArg;
  }

  private XobjInt callLoopSchedFuncNodes(XMPnodes nodesObj, XobjList nodesSubscriptList,
                                         CforBlock forBlock, CforBlock schedBaseBlock) throws XMPexception {
    LineNo lnObj = forBlock.getLineNo();

    Xobject loopIndex = forBlock.getInductionVar();
    Xtype loopIndexType = loopIndex.Type();

    if (!XMPutil.isIntegerType(loopIndexType))
      XMP.error(lnObj, "loop index variable has a non-integer type");

    String funcTypeSurfix = XMPutil.getTypeName(loopIndexType);
    String loopIndexName = loopIndex.getSym();

    XobjList funcArgs = Xcons.List();
    funcArgs.add(forBlock.getLowerBound());
    funcArgs.add(forBlock.getUpperBound());
    funcArgs.add(forBlock.getStep());

    int nodesIndex = 0;
    int nodesDim = nodesObj.getDim();
    XobjInt nodesIndexArg = null;
    for (XobjArgs i = nodesSubscriptList.getArgs(); i != null; i = i.nextArgs()) {
      if (nodesIndex >= nodesDim)
        XMP.error(lnObj, "wrong nodes dimensions, too many");

      String s = i.getArg().getString();
      if (s.equals(loopIndexName)) {
        if (nodesIndexArg != null)
          XMP.error(lnObj, "loop index '" + loopIndexName + "' is already described");

        nodesIndexArg = Xcons.IntConstant(nodesIndex);
      }

      nodesIndex++;
    }

    if(nodesIndexArg == null)
      XMP.error(lnObj, "cannot find index '" + loopIndexName + "' reference in <on-ref>");

    if(nodesIndex != nodesDim)
      XMP.error(lnObj, "wrong nodes dimensions, too few");

    Ident parallelInitId = declLoopSchedIdent(schedBaseBlock,
                                              "_XCALABLEMP_loop_init_" + loopIndexName, loopIndexType);
    Ident parallelCondId = declLoopSchedIdent(schedBaseBlock,
                                              "_XCALABLEMP_loop_cond_" + loopIndexName, loopIndexType);
    Ident parallelStepId = declLoopSchedIdent(schedBaseBlock,
                                              "_XCALABLEMP_loop_step_" + loopIndexName, loopIndexType);

    forBlock.setLowerBound(parallelInitId.Ref());
    forBlock.setUpperBound(parallelCondId.Ref());
    forBlock.setStep(parallelStepId.Ref());

    forBlock.getCondBBlock().setExpr(Xcons.binaryOp(Xcode.LOG_LT_EXPR, loopIndex, parallelCondId.Ref()));

    funcArgs.add(parallelInitId.getAddr());
    funcArgs.add(parallelCondId.getAddr());
    funcArgs.add(parallelStepId.getAddr());

    funcArgs.add(nodesObj.getDescId().Ref());
    funcArgs.add(nodesIndexArg);

    Ident funcId = _globalDecl.declExternFunc("_XCALABLEMP_sched_loop_nodes_" + funcTypeSurfix);

    schedBaseBlock.insert(funcId.Call(funcArgs));

    return nodesIndexArg;
  }

  private Ident declLoopSchedIdent(CforBlock b, String identName, Xtype type) {
    BlockList bl = b.getParent();
    Ident id = bl.findLocalIdent(identName);
    if (id == null) {
      id = Ident.Local(identName, type);
      bl.addIdent(id);
    }

    return id;
  }

  private void translateReflect(PragmaBlock pb) {
    LineNo lnObj = pb.getLineNo();
    System.out.println("REFLECT:" + pb.toXobject().toString());
  }

  private void translateBarrier(PragmaBlock pb) throws XMPexception {
    LineNo lnObj = pb.getLineNo();

    // start translation
    XobjList barrierDecl = (XobjList)pb.getClauses();
    XMPobjectTable localObjectTable = XMPlocalDecl.declObjectTable(pb);

    // create function call
    XobjList onRef = (XobjList)barrierDecl.getArg(0);
    if (onRef == null) pb.replace(createFuncCallBlock("_XCALABLEMP_barrier_EXEC", null));
    else {
      XMPtriplet<String, Boolean, XobjList> execOnRefArgs = createExecOnRefArgs(lnObj, onRef, localObjectTable);
      String execFuncSurfix = execOnRefArgs.getFirst();
      boolean splitComm = execOnRefArgs.getSecond().booleanValue();
      XobjList execFuncArgs = execOnRefArgs.getThird();
      if (splitComm) {
        BlockList barrierBody = Bcons.blockList(createFuncCallBlock("_XCALABLEMP_barrier_EXEC", null));

        // setup barrier finalizer
        setupFinalizer(lnObj, barrierBody, _globalDecl.declExternFunc("_XCALABLEMP_pop_n_free_nodes"), null);

        // create function call
        Ident execFuncId = _env.declExternIdent("_XCALABLEMP_exec_task_" + execFuncSurfix, Xtype.Function(Xtype.boolType));
        pb.replace(Bcons.IF(BasicBlock.Cond(execFuncId.Call(execFuncArgs)), barrierBody, null));
      }
      else pb.replace(createFuncCallBlock("_XCALABLEMP_barrier_" + execFuncSurfix, execFuncArgs));
      // execFuncSurfix is {GLOBAL, NODES, TEMPLATE}_ENTIRE, execFuncArgs is LIST({null, NODES desc, TEMPLATE desc})
    }
  }

  private Block createFuncCallBlock(String funcName, XobjList funcArgs) {
    Ident funcId = _globalDecl.declExternFunc(funcName);
    return Bcons.Statement(funcId.Call(funcArgs));
  }

  private void translateReduction(PragmaBlock pb) throws XMPexception {
    LineNo lnObj = pb.getLineNo();

    // start translation
    XobjList reductionDecl = (XobjList)pb.getClauses();
    XMPobjectTable localObjectTable = XMPlocalDecl.declObjectTable(pb);

    // create function arguments
    XobjList reductionRef = (XobjList)reductionDecl.getArg(0);
    Vector<XobjList> reductionFuncArgsList = createReductionArgsList(reductionRef, pb);

    // create function call
    XobjList onRef = (XobjList)reductionDecl.getArg(1);
    if (onRef == null) pb.replace(createReductionFuncCallBlock("_XCALABLEMP_reduce_EXEC", null, reductionFuncArgsList));
    else {
      XMPtriplet<String, Boolean, XobjList> execOnRefArgs = createExecOnRefArgs(lnObj, onRef, localObjectTable);
      String execFuncSurfix = execOnRefArgs.getFirst();
      boolean splitComm = execOnRefArgs.getSecond().booleanValue();
      XobjList execFuncArgs = execOnRefArgs.getThird();
      if (splitComm) {
        BlockList reductionBody = Bcons.blockList(createReductionFuncCallBlock("_XCALABLEMP_reduce_EXEC",
                                                                               null, reductionFuncArgsList));

        // setup reduction finalizer
        setupFinalizer(lnObj, reductionBody, _globalDecl.declExternFunc("_XCALABLEMP_pop_n_free_nodes"), null);

        // create function call
        Ident execFuncId = _env.declExternIdent("_XCALABLEMP_exec_task_" + execFuncSurfix, Xtype.Function(Xtype.boolType));
        pb.replace(Bcons.IF(BasicBlock.Cond(execFuncId.Call(execFuncArgs)), reductionBody, null));
      }
      else {
        // execFuncSurfix is {GLOBAL, NODES}_ENTIRE, execFuncArgs is LIST({null, NODES desc})
        pb.replace(createReductionFuncCallBlock("_XCALABLEMP_reduce_" + execFuncSurfix,
                                                execFuncArgs.operand(), reductionFuncArgsList));
      }
    }
  }

  private Vector<XobjList> createReductionArgsList(XobjList reductionRef, PragmaBlock pb) throws XMPexception {
    LineNo lnObj = pb.getLineNo();
    Vector<XobjList> returnVector = new Vector<XobjList>();

    XobjInt reductionOp = (XobjInt)reductionRef.getArg(0);

    XobjList reductionSpecList = (XobjList)reductionRef.getArg(1);
    for (XobjArgs i = reductionSpecList.getArgs(); i != null; i = i.nextArgs()) {
      String specName = i.getArg().getString();

      XMPpair<Ident, Xtype> typedSpec = findTypedVar(specName, pb);
      Ident specId = typedSpec.getFirst();
      Xtype specType = typedSpec.getSecond();

      XobjLong count = null;
      XobjInt elmtType = null;
      switch (specType.getKind()) {
        case Xtype.BASIC:
          {
            BasicType basicSpecType = (BasicType)specType;
            checkReductionType(lnObj, specName, basicSpecType);

            count = Xcons.LongLongConstant(0, 1);
            elmtType = Xcons.IntConstant(basicSpecType.getBasicType() + 200);
            break;
          }
        case Xtype.ARRAY:
          {
            ArrayType arraySpecType = (ArrayType)specType;
            if (arraySpecType.getArrayElementType().getKind() != Xtype.BASIC)
              XMP.error(lnObj, "array '" + specName + "' has has a wrong data type for reduction");

            BasicType basicSpecType = (BasicType)arraySpecType.getArrayElementType();
            checkReductionType(lnObj, specName, basicSpecType);

            count = Xcons.LongLongConstant(0, getArrayElmtCount(arraySpecType));
            elmtType = Xcons.IntConstant(basicSpecType.getBasicType() + 200);
            break;
          }
        default:
          XMP.error(lnObj, "'" + specName + "' has a wrong data type for reduction");
      }

      returnVector.add(Xcons.List(specId.getAddr(), count, elmtType, reductionOp));
    }

    return returnVector;
  }

  private void checkReductionType(LineNo lnObj, String name, BasicType type) throws XMPexception {
    switch (type.getBasicType()) {
      case BasicType.BOOL:
      case BasicType.CHAR:
      case BasicType.UNSIGNED_CHAR:
      case BasicType.SHORT:
      case BasicType.UNSIGNED_SHORT:
      case BasicType.INT:
      case BasicType.UNSIGNED_INT:
      case BasicType.LONG:
      case BasicType.UNSIGNED_LONG:
      case BasicType.LONGLONG:
      case BasicType.UNSIGNED_LONGLONG:
      case BasicType.FLOAT:
      case BasicType.DOUBLE:
      case BasicType.LONG_DOUBLE:
      case BasicType.FLOAT_IMAGINARY:
      case BasicType.DOUBLE_IMAGINARY:
      case BasicType.LONG_DOUBLE_IMAGINARY:
      case BasicType.FLOAT_COMPLEX:
      case BasicType.DOUBLE_COMPLEX:
      case BasicType.LONG_DOUBLE_COMPLEX:
        break;
      default:
        XMP.error(lnObj, "'" + name + "' has a wrong data type for reduction");
    }
  }

  private Block createReductionFuncCallBlock(String funcName, Xobject execDesc, Vector<XobjList> funcArgsList) {
    Ident funcId = _globalDecl.declExternFunc(funcName);
    BlockList funcCallList = Bcons.emptyBody();
    Iterator<XobjList> it = funcArgsList.iterator();
    while (it.hasNext()) {
      XobjList funcArgs = it.next();
      if (execDesc != null) funcArgs.cons(execDesc);

      funcCallList.add(Bcons.Statement(funcId.Call(funcArgs)));
    }
    return Bcons.COMPOUND(funcCallList);
  }

  private XMPpair<Ident, Xtype> findTypedVar(String name, PragmaBlock pb) throws XMPexception {
    LineNo lnObj = pb.getLineNo();
    Ident id = pb.findVarIdent(name);
    if (id == null)
      XMP.error(lnObj, "cannot find '" + name + "'");

    Xtype type = id.Type();
    if (type == null)
      XMP.error(lnObj, "'" + name + "' has no type");

    return new XMPpair<Ident, Xtype>(id, type);
  }

  public long getArrayElmtCount(Xtype type) {
    if (type.isArray()) {
      ArrayType arrayType = (ArrayType)type;
      long arraySize = arrayType.getArraySize();
      return arraySize * getArrayElmtCount(arrayType.getRef());
    }
    else return 1;
  }

  private void translateBcast(PragmaBlock pb) throws XMPexception {
    LineNo lnObj = pb.getLineNo();

    // start translation
    XobjList bcastDecl = (XobjList)pb.getClauses();
    XMPobjectTable localObjectTable = XMPlocalDecl.declObjectTable(pb);

    // create function arguments
    XobjList varList = (XobjList)bcastDecl.getArg(0);
    Vector<XobjList> bcastArgsList = createBcastArgsList(lnObj, varList, pb);

    // create function call
    XobjList fromRef = (XobjList)bcastDecl.getArg(1);
    XMPpair<String, XobjList> execFromRefArgs = null;
    if (fromRef != null) execFromRefArgs = createExecFromRefArgs(lnObj, fromRef, localObjectTable);

    XobjList onRef = (XobjList)bcastDecl.getArg(2);
    if (onRef == null) pb.replace(createBcastFuncCallBlock(lnObj, "_XCALABLEMP_bcast_EXEC", null, bcastArgsList, execFromRefArgs));
    else {
      XMPtriplet<String, Boolean, XobjList> execOnRefArgs = createExecOnRefArgs(lnObj, onRef, localObjectTable);

      String execFuncSurfix = execOnRefArgs.getFirst();
      boolean splitComm = execOnRefArgs.getSecond().booleanValue();
      XobjList execFuncArgs = execOnRefArgs.getThird();
      if (splitComm) {
        BlockList bcastBody = Bcons.blockList(createBcastFuncCallBlock(lnObj, "_XCALABLEMP_bcast_EXEC",
                                                                       null, bcastArgsList, execFromRefArgs));

        // setup reduction finalizer
        setupFinalizer(lnObj, bcastBody, _globalDecl.declExternFunc("_XCALABLEMP_pop_n_free_nodes"), null);

        // create function call
        Ident execFuncId = _env.declExternIdent("_XCALABLEMP_exec_task_" + execFuncSurfix, Xtype.Function(Xtype.boolType));
        pb.replace(Bcons.IF(BasicBlock.Cond(execFuncId.Call(execFuncArgs)), bcastBody, null));
      }
      else {
        // execFuncSurfix is {GLOBAL, NODES}_ENTIRE, execFuncArgs is LIST({null, NODES desc})
        pb.replace(createBcastFuncCallBlock(lnObj, "_XCALABLEMP_bcast_" + execFuncSurfix,
                                            execFuncArgs.operand(), bcastArgsList, execFromRefArgs));
      }
    }
  }

  private Vector<XobjList> createBcastArgsList(LineNo lnObj,
                                               XobjList varList, PragmaBlock pb) throws XMPexception {
    Vector<XobjList> returnVector = new Vector<XobjList>();

    for (XobjArgs i = varList.getArgs(); i != null; i = i.nextArgs()) {
      String varName = i.getArg().getString();

      XMPpair<Ident, Xtype> typedSpec = findTypedVar(varName, pb);
      Ident varId = typedSpec.getFirst();
      Xtype varType = typedSpec.getSecond();

      XobjLong count = null;
      switch (varType.getKind()) {
        case Xtype.BASIC:
        case Xtype.STRUCT:
        case Xtype.UNION:
          count = Xcons.LongLongConstant(0, 1);
          break;
        case Xtype.ARRAY:
          {
            ArrayType arrayVarType = (ArrayType)varType;
            switch (arrayVarType.getArrayElementType().getKind()) {
              case Xtype.BASIC:
              case Xtype.STRUCT:
              case Xtype.UNION:
                break;
              default:
                XMP.error(lnObj, "array '" + varName + "' has has a wrong data type for broadcast");
            }

            count = Xcons.LongLongConstant(0, getArrayElmtCount(arrayVarType));
            break;
          }
        default:
          XMP.error(lnObj, "'" + varName + "' has a wrong data type for broadcast");
      }
      Xobject elmtSize = Xcons.SizeOf(varType);

      returnVector.add(Xcons.List(varId.getAddr(), count, elmtSize));
    }

    return returnVector;
  }

  private Block createBcastFuncCallBlock(LineNo lnObj, String funcName, Xobject execDesc, Vector<XobjList> funcArgsList,
                                         XMPpair<String, XobjList> execFromRefArgs) throws XMPexception {
    Ident funcId = null;
    XobjList fromRef = null;
    if (execFromRefArgs == null) funcId = _globalDecl.declExternFunc(funcName + "_OMITTED");
    else {
      funcId = _globalDecl.declExternFunc(funcName + "_" + execFromRefArgs.getFirst());
      fromRef = execFromRefArgs.getSecond();
    }

    BlockList funcCallList = Bcons.emptyBody();
    Iterator<XobjList> it = funcArgsList.iterator();
    while (it.hasNext()) {
      XobjList funcArgs = it.next();
      if (execDesc != null) funcArgs.cons(execDesc);
      if (execFromRefArgs != null) XMPutil.mergeLists(funcArgs, fromRef);

      funcCallList.add(Bcons.Statement(funcId.Call(funcArgs)));
    }
    return Bcons.COMPOUND(funcCallList);
  }

  private XMPpair<String, XobjList> createExecFromRefArgs(LineNo lnObj, XobjList fromRef,
                                                          XMPobjectTable localObjectTable) throws XMPexception {
    if (fromRef.getArg(0) == null) {
      // execute on global communicator
      XobjList globalRef = (XobjList)fromRef.getArg(1);

      XobjList execFuncArgs = Xcons.List();
      // lower
      if (globalRef.getArg(0) == null)
        XMP.error(lnObj, "lower bound cannot be omitted in <from-ref>");
      else execFuncArgs.add(globalRef.getArg(0));

      // upper
      if (globalRef.getArg(1) == null)
        XMP.error(lnObj, "upper bound cannot be omitted in <from-ref>");
      else execFuncArgs.add(globalRef.getArg(1));

      // stride
      if (globalRef.getArg(2) == null) execFuncArgs.add(Xcons.IntConstant(1));
      else execFuncArgs.add(globalRef.getArg(2));

      return new XMPpair<String, XobjList>(new String("GLOBAL"), execFuncArgs);
    }
    else {
      // execute on <object-ref>

      // check object name collision
      String objectName = fromRef.getArg(0).getString();
      XMPobject fromRefObject = findXMPobject(lnObj, objectName, localObjectTable);
      if (fromRefObject.getKind() == XMPobject.TEMPLATE)
        XMP.error(lnObj, "template cannot be used in <from-ref>");

      // create arguments
      if (fromRef.getArg(1) == null)
        XMP.error(lnObj, "multiple source nodes indicated in bcast directive");
      else {
        XobjList execFuncArgs = Xcons.List(fromRefObject.getDescId().Ref());

        int refIndex = 0;
        int refDim = fromRefObject.getDim();
        for (XobjArgs i = fromRef.getArg(1).getArgs(); i != null; i = i.nextArgs()) {
          if (refIndex == refDim)
            XMP.error(lnObj, "wrong nodes dimension indicated, too many");

          XobjList t = (XobjList)i.getArg();
          if (t == null) execFuncArgs.add(Xcons.Cast(Xtype.intType, Xcons.IntConstant(1)));
          else {
            execFuncArgs.add(Xcons.Cast(Xtype.intType, Xcons.IntConstant(0)));

            // lower
            if (t.getArg(0) == null)
              XMP.error(lnObj, "lower bound cannot be omitted in <from-ref>");
            else execFuncArgs.add(Xcons.Cast(Xtype.intType, t.getArg(0)));

            // upper
            if (t.getArg(1) == null)
              XMP.error(lnObj, "upper bound cannot be omitted in <from-ref>");
            else execFuncArgs.add(Xcons.Cast(Xtype.intType, t.getArg(1)));

            // stride
            if (t.getArg(2) == null) execFuncArgs.add(Xcons.Cast(Xtype.intType, Xcons.IntConstant(1)));
            else execFuncArgs.add(Xcons.Cast(Xtype.intType, t.getArg(2)));
          }

          refIndex++;
        }

        if (refIndex != refDim)
          XMP.error(lnObj, "the number of <nodes/template-subscript> should be the same with the dimension");

        return new XMPpair<String, XobjList>(new String("NODES"), execFuncArgs);
      }
    }

    // not reach here
    return null;
  }

  private XMPtriplet<String, Boolean, XobjList> createExecOnRefArgs(LineNo lnObj, XobjList onRef,
                                                                    XMPobjectTable localObjectTable) throws XMPexception {
    if (onRef.getArg(0) == null) {
      // execute on global communicator
      XobjList globalRef = (XobjList)onRef.getArg(1);

      boolean splitComm = false;
      XobjList tempArgs = Xcons.List();
      // lower
      if (globalRef.getArg(0) == null) tempArgs.add(Xcons.IntConstant(1));
      else {
        splitComm = true;
        tempArgs.add(globalRef.getArg(0));
      }
      // upper
      if (globalRef.getArg(1) == null) tempArgs.add(_globalDecl.getWorldSizeId().Ref());
      else {
        splitComm = true;
        tempArgs.add(globalRef.getArg(1));
      }
      // stride
      if (globalRef.getArg(2) == null) tempArgs.add(Xcons.IntConstant(1));
      else {
        splitComm = true;
        tempArgs.add(globalRef.getArg(2));
      }

      String execFuncSurfix = null;
      XobjList execFuncArgs = null;
      if (splitComm) {
        execFuncSurfix = "GLOBAL_PART";
        execFuncArgs = tempArgs;
      }
      else {
        execFuncSurfix = "NODES_ENTIRE";
        execFuncArgs = Xcons.List(_globalDecl.getWorldDescId().Ref());
      }

      return new XMPtriplet<String, Boolean, XobjList>(execFuncSurfix, new Boolean(splitComm), execFuncArgs);
    }
    else {
      // execute on <object-ref>

      // check object name collision
      String objectName = onRef.getArg(0).getString();
      XMPobject onRefObject = findXMPobject(lnObj, objectName, localObjectTable);

      Xobject ontoNodesRef = null;
      Xtype castType = null;
      switch (onRefObject.getKind()) {
        case XMPobject.NODES:
          ontoNodesRef = onRefObject.getDescId().Ref();
          castType = Xtype.intType;
          break;
        case XMPobject.TEMPLATE:
          {
            XMPnodes ontoNodes = ((XMPtemplate)onRefObject).getOntoNodes();
            if (ontoNodes == null)
              XMP.error(lnObj, "template '" + objectName + "' is not distributed");

            ontoNodesRef = ontoNodes.getDescId().Ref();
            castType = Xtype.longlongType;
            break;
          }
        default:
          XMP.fatal("unknown object type");
      }

      // create arguments
      if (onRef.getArg(1) == null)
        return new XMPtriplet<String, Boolean, XobjList>(new String("NODES_ENTIRE"), new Boolean(false), Xcons.List(ontoNodesRef));
      else {
        boolean splitComm = false;
        int refIndex = 0;
        int refDim = onRefObject.getDim();
        boolean getUpper = false;
        XobjList tempArgs = Xcons.List();
        for (XobjArgs i = onRef.getArg(1).getArgs(); i != null; i = i.nextArgs()) {
          if (refIndex == refDim)
            XMP.error(lnObj, "wrong nodes dimension indicated, too many");

          XobjList t = (XobjList)i.getArg();
          if (t == null) {
            splitComm = true;
            tempArgs.add(Xcons.Cast(Xtype.intType, Xcons.IntConstant(1)));
          }
          else {
            tempArgs.add(Xcons.Cast(Xtype.intType, Xcons.IntConstant(0)));

            // lower
            if (t.getArg(0) == null) tempArgs.add(Xcons.Cast(castType, Xcons.IntConstant(1)));
            else {
              splitComm = true;
              tempArgs.add(Xcons.Cast(castType, t.getArg(0)));
            }
            // upper
            if (t.getArg(1) == null) {
              Xobject onRefUpper = onRefObject.getUpperAt(refIndex);
              if (onRefUpper == null) getUpper = true;
              else tempArgs.add(Xcons.Cast(castType, onRefUpper));
            }
            else {
              splitComm = true;
              tempArgs.add(Xcons.Cast(castType, t.getArg(1)));
            }
            // stride
            if (t.getArg(2) == null) tempArgs.add(Xcons.Cast(castType, Xcons.IntConstant(1)));
            else {
              splitComm = true;
              tempArgs.add(Xcons.Cast(castType, t.getArg(2)));
            }
          }

          refIndex++;
        }

        if (refIndex != refDim)
          XMP.error(lnObj, "the number of <nodes/template-subscript> should be the same with the dimension");

        if (splitComm) {
          String execFuncSurfix = null;
          XobjList execFuncArgs = null;
          execFuncArgs = tempArgs;
          switch (onRefObject.getKind()) {
            case XMPobject.NODES:
              execFuncSurfix = "NODES_PART";
              execFuncArgs.cons(ontoNodesRef);
              break;
            case XMPobject.TEMPLATE:
              execFuncSurfix = "TEMPLATE_PART";
              execFuncArgs.cons(ontoNodesRef);
              execFuncArgs.cons(((XMPtemplate)onRefObject).getDescId().Ref());
              break;
            default:
              XMP.fatal("unknown object type");
          }

          if (getUpper) execFuncArgs.cons(Xcons.IntConstant(1));
          else          execFuncArgs.cons(Xcons.IntConstant(0));

          return new XMPtriplet<String, Boolean, XobjList>(execFuncSurfix, new Boolean(splitComm), execFuncArgs);
        }
        else
          return new XMPtriplet<String, Boolean, XobjList>(new String("NODES_ENTIRE"),
                                                           new Boolean(splitComm), Xcons.List(ontoNodesRef));
      }
    }
  }

  private void setupFinalizer(LineNo lbObj, BlockList body, Ident funcId, XobjList args) throws XMPexception {
    BlockIterator i = new topdownBlockIterator(body);
    for (i.init(); !i.end(); i.next()) {
      Block b = i.getBlock();
      if (b.Opcode() == Xcode.GOTO_STATEMENT)
        XMP.error(lbObj, "cannot use goto statement here");
      else if (b.Opcode() == Xcode.RETURN_STATEMENT)
        b.insert(funcId.Call(args));
    }
    body.add(funcId.Call(args));
  }

  public static void checkDeclPragmaLocation(PragmaBlock pb) throws XMPexception {
/*
    LineNo lnObj = pb.getLineNo();
    String pragmaNameL = pb.getPragma().toLowerCase();

    // check parent
    Block parentBlock = pb.getParentBlock();
    if (parentBlock.Opcode() != Xcode.COMPOUND_STATEMENT)
      XMP.error(lnObj, pragmaNameL + " directive should be written before declarations, statements and executable directives");
    else {
      BlockList parent = pb.getParent();
      Xobject declList = parent.getDecls();
      if (declList != null) {
        if (declList.operand() != null)
          XMP.error(lnObj, pragmaNameL + " directive should be written before declarations, statements and executable directives");
      }

      if (parentBlock.getParentBlock().Opcode() != Xcode.FUNCTION_DEFINITION)
        XMP.error(lnObj, pragmaNameL + " directive should be written before declarations, statements and executable directives");
    }

    // check previous blocks
    for (Block prevBlock = pb.getPrev(); prevBlock != null; prevBlock = prevBlock.getPrev()) {
      if (prevBlock.Opcode() == Xcode.XMP_PRAGMA) {
        XMPpragma prevPragma = XMPpragma.valueOf(((PragmaBlock)prevBlock).getPragma());
        switch (XMPpragma.valueOf(((PragmaBlock)prevBlock).getPragma())) {
          case NODES:
          case TEMPLATE:
          case DISTRIBUTE:
          case ALIGN:
          case SHADOW:
            continue;
          default:
            XMP.error(lnObj, pragmaNameL + " directive should be written before declarations, statements and executable directives");
        }
      }
      else
        XMP.error(lnObj, pragmaNameL + " directive should be written before declarations, statements and executable directives");
    }
*/
    // XXX delete this
    return;
  }

  private XMPobject findXMPobject(LineNo lnObj, String objectName, XMPobjectTable localObjectTable) throws XMPexception {
    XMPobject object = localObjectTable.getObject(objectName);
    if (object == null) {
      object = _globalObjectTable.getObject(objectName);
      if (object == null)
        XMP.error(lnObj, "cannot find '" + objectName + "' nodes/template");
    }

    return object;
  }

  private XMPtemplate findXMPtemplate(LineNo lnObj, String templateName, XMPobjectTable localObjectTable) throws XMPexception {
    XMPtemplate t = localObjectTable.getTemplate(templateName);
    if (t == null) {
      t = _globalObjectTable.getTemplate(templateName);
      if (t == null)
        XMP.error(lnObj, "template '" + templateName + "' is not declared");
    }

    return t;
  }

  private XMPnodes findXMPnodes(LineNo lnObj, String nodesName, XMPobjectTable localObjectTable) throws XMPexception {
    XMPnodes n = localObjectTable.getNodes(nodesName);
    if (n == null) {
      n = _globalObjectTable.getNodes(nodesName);
      if (n == null)
        XMP.error(lnObj, "nodes '" + nodesName + "' is not declared");
    }

    return n;
  }
}
