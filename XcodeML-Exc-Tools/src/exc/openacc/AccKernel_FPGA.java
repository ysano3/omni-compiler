/* -*- Mode: java; c-basic-offset:2 ; indent-tabs-mode:nil ; -*- */
package exc.openacc;

import exc.block.*;
import exc.object.*;
import exc.openacc.AccInformation.VarListClause;

import java.beans.beancontext.BeanContextServiceProvider;
import java.util.*;
import java.util.concurrent.BlockingQueue;

// AccKernel for FPGA
public class AccKernel_FPGA extends AccKernel {

  ArrayDeque<Loop> loopStack;
  SharedMemory sharedMemory;
  ReductionManager reductionManager;
  StackMemory _globalMemoryStack;
  final int UNROLL_MAX = 16;
  private int numKernels = 1; // default
  private int fpgaKernelNum = 0;
  private String originalFuncName;
  final Set<Ident> kernelChannels = new HashSet<Ident>();
  final Set<Ident> globalArrayList = new HashSet<Ident>();
  final Set<ACCvar> onBramList = new HashSet<ACCvar>();
  final ArrayList<Ident> bramIterList = new ArrayList<Ident>();
  final ArrayList<Ident> onBramIds = new ArrayList<Ident>();
  final ArrayList<Ident> globalIds = new ArrayList<Ident>();

  final String ACC_CL_KERNEL_LAUNCHER_MULTI = ACC_CL_KERNEL_LAUNCHER_NAME + "_multi";
  final String ACC_CL_SEND_FUNC_NAME = "write_channel_intel";
  final String ACC_CL_RECV_FUNC_NAME = "read_channel_intel";

  public AccKernel_FPGA(ACCglobalDecl decl, PragmaBlock pb, AccInformation info, List<Block> kernelBlocks) {
    super(decl,pb,info,kernelBlocks);
  }

  void initInternalClasses()
  {
    loopStack = new ArrayDeque<Loop>();
    sharedMemory = new SharedMemory();
    reductionManager = new ReductionManager();
    _globalMemoryStack = new StackMemory("_ACC_gmem_stack", Ident.Param("_ACC_gmem_stack_addr", Xtype.voidPtrType));

    // if(ACC.debug_flag)
    System.out.println("AccKernel_FPGA enabled ...");
  }

  public Block makeLaunchFuncCallBlock() {
    List<Block> kernelBody = _kernelBlocks;
    kernelChannels.clear();
    if(_kernelInfo.hasClause(ACCpragma.NUM_KERNELS)) {
      Xobject numKernelsExpr = _kernelInfo.getIntExpr(ACCpragma.NUM_KERNELS);
      if(numKernelsExpr != null && isCalculatable(numKernelsExpr)) {
        numKernels = calcXobject(numKernelsExpr);
      } else {
        numKernels = 1;
      }
    } else {
      numKernels = 1;
    }
    // System.out.println("makeLaunchFuncCallBlock() [numKernels = " + numKernels + "]");

    if(numKernels > 1) {
      String launchFuncName = "";
      launchFuncName = ACC_CL_KERNEL_LAUNCHER_MULTI;
      XobjectDef deviceKernelDef = null;

      for(int i = 0; i < numKernels; i++) {
        fpgaKernelNum = i;
        bramIterList.clear();

        String funcName = getFuncInfo(_pb).getArg(0).getString();
        int lineNo = kernelBody.get(0).getLineNo().lineNo();
        String kernelMainName = ACC_FUNC_PREFIX + funcName + "_L" + lineNo + "_" + i;
        originalFuncName = ACC_FUNC_PREFIX + funcName + "_L" + lineNo;

        collectOuterVar();

        //make deviceKernelDef
        String deviceKernelName = kernelMainName + ACC_GPU_DEVICE_FUNC_SUFFIX;
        deviceKernelDef = makeDeviceKernelDef(deviceKernelName, _outerIdList,  kernelBody);

        //add deviceKernel and launchFunction
        XobjectFile devEnv = _decl.getEnvDevice();
        devEnv.add(deviceKernelDef);
      }

      return makeLaunchFuncBlock(launchFuncName, deviceKernelDef);

    }

    String funcName = getFuncInfo(_pb).getArg(0).getString();
    int lineNo = kernelBody.get(0).getLineNo().lineNo();
    String kernelMainName = ACC_FUNC_PREFIX + funcName + "_L" + lineNo;
    String launchFuncName = "";
    launchFuncName = ACC_CL_KERNEL_LAUNCHER_NAME;

    collectOuterVar();

    //make deviceKernelDef
    String deviceKernelName = kernelMainName + ACC_GPU_DEVICE_FUNC_SUFFIX;
    XobjectDef deviceKernelDef = makeDeviceKernelDef(deviceKernelName, _outerIdList, kernelBody);

    //add deviceKernel and launchFunction
    XobjectFile devEnv = _decl.getEnvDevice();
    devEnv.add(deviceKernelDef);

    return makeLaunchFuncBlock(launchFuncName, deviceKernelDef);
  }

  Block makeKernelLaunchBlock(String launchFuncName, String kernelFuncName, XobjList kernelFuncArgs, Ident confId, Xobject asyncExpr)
  {
    BlockList body = Bcons.emptyBody();

    XobjList argDecls = Xcons.List();
    XobjList argSizeDecls = Xcons.List();
    for(int i = 0; i < numKernels; i++) {
      for(Xobject x : kernelFuncArgs){
        if(x.Opcode() != Xcode.CAST_EXPR) {
          //FIXME use AddrOFVar
          argDecls.add(Xcons.AddrOf(x));
        }else{
          argDecls.add(Xcons.AddrOfVar(x.getArg(0)));
        }
        if(x.Type().isPointer()) {
          argSizeDecls.add(Xcons.SizeOf(Xtype.voidPtrType));
        }else {
          argSizeDecls.add(Xcons.SizeOf(x.Type()));
        }
      }
    }
    Ident argSizesId = body.declLocalIdent("_ACC_argsizes", Xtype.Array(Xtype.unsignedlonglongType, null), StorageClass.AUTO, argSizeDecls);
    Ident argsId = body.declLocalIdent("_ACC_args", Xtype.Array(Xtype.voidPtrType, null), StorageClass.AUTO, argDecls);

    Ident launchFuncId = ACCutil.getMacroFuncId(launchFuncName, Xtype.voidType);
    int kernelNum;
    if(numKernels > 1) {
      kernelNum = _decl.declKernel(originalFuncName + "_" + 0 + ACC_GPU_DEVICE_FUNC_SUFFIX);
      for(int i = 1; i < numKernels; i++) {
        _decl.declKernel(originalFuncName + "_" + i + ACC_GPU_DEVICE_FUNC_SUFFIX);
      }
    } else {
      kernelNum = _decl.declKernel(kernelFuncName);
    }
    Ident programId = _decl.getProgramId();
    int numArgs = kernelFuncArgs.Nargs();

    Xobject launchFuncArgs;

    if(numKernels > 1) {
      launchFuncArgs = Xcons.List(
                                        programId.Ref(),
                                        Xcons.IntConstant(kernelNum),
                                        confId.Ref(),
                                        asyncExpr,
                                        Xcons.IntConstant(numArgs),
                                        argSizesId.Ref(),
                                        argsId.Ref(),
                                        Xcons.IntConstant(numKernels));
    } else {
      launchFuncArgs = Xcons.List(
                                        programId.Ref(),
                                        Xcons.IntConstant(kernelNum),
                                        confId.Ref(),
                                        asyncExpr,
                                        Xcons.IntConstant(numArgs),
                                        argSizesId.Ref(),
                                        argsId.Ref());
    }

    body.add(launchFuncId.Call(launchFuncArgs));
    return Bcons.COMPOUND(body);
  }

  Block makeKernelLaunchBlockCUDA(Ident deviceKernelId, XobjList kernelArgs, XobjList conf, Xobject asyncExpr)
  {
    Xobject deviceKernelCall = deviceKernelId.Call(kernelArgs);
    //FIXME merge GPU_FUNC_CONF and GPU_FUNC_CONF_ASYNC
    deviceKernelCall.setProp(ACCgpuDecompiler.GPU_FUNC_CONF, conf);
    deviceKernelCall.setProp(ACCgpuDecompiler.GPU_FUNC_CONF_ASYNC, Xcons.List(asyncExpr));
    if (sharedMemory.isUsed()) {
      Xobject maxSmSize = sharedMemory.getMaxSize();
      deviceKernelCall.setProp(ACCgpuDecompiler.GPU_FUNC_CONF_SHAREDMEMORY, maxSmSize);
    }
    return Bcons.Statement(deviceKernelCall);
  }

  Xobject makeLaunchFuncArg(ACCvar var){
    if (var.isArray()) {
      Ident devicePtrId = var.getDevicePtr();
      Xobject devicePtr = devicePtrId.Ref();

      Xtype type = var.getId().Type();
      if(! type.equals(devicePtrId.Type())){
        return Xcons.Cast(type, devicePtr);
      }
      return devicePtr;
    } else{
      Ident id = var.getId();

      if (_useMemPoolOuterIdSet.contains(id)) {
        return id.getAddr(); //host scalar pointer
      }

      if(!var.isArray() && var.isFirstprivate()){
        return id.Ref(); //host scalar data
      }

      Ident devicePtrId = var.getDevicePtr();
      if(ACC.debug_flag) System.out.println("devicePrtId="+devicePtrId);
      Xobject devicePtr = devicePtrId.Ref();
      Xtype elmtType = var.getElementType();
      if(! elmtType.equals(devicePtrId.Type())){
        return Xcons.Cast(Xtype.Pointer(elmtType), devicePtr);
      }
      return devicePtr;
    }
  }

  Xobject getAsyncExpr(){
    if (! _kernelInfo.hasClause(ACCpragma.ASYNC)) {
      return Xcons.IntConstant(ACC.ACC_ASYNC_SYNC);
    }

    Xobject asyncExpr = _kernelInfo.getIntExpr(ACCpragma.ASYNC);
    if (asyncExpr != null) {
      return asyncExpr;
    } else {
      return Xcons.IntConstant(ACC.ACC_ASYNC_NOVAL);
    }
  }

  Ident findInnerBlockIdent(Block topBlock, BlockList body, String name) {
    // if the id exists between topBlock to bb, the id is not outerId
    for (BlockList b_list = body; b_list != null; b_list = b_list.getParentList()) {
      Ident localId = b_list.findLocalIdent(name);
      if (localId != null) return localId;
      if (b_list == topBlock.getParent()) break;
    }
    return null;
  }

  class DeviceKernelBuildInfo {
    final private List<Block> initBlockList = new ArrayList<Block>();
    final private List<Block> finalizeBlockList = new ArrayList<Block>();
    final private XobjList paramIdList = Xcons.IDList();
    final private XobjList localIdList = Xcons.IDList();

    public List<Block> getInitBlockList() {
      return initBlockList;
    }

    public List<Block> getFinalizeBlockList(){
      return finalizeBlockList;
    }

    public void addInitBlock(Block b) {
      initBlockList.add(b);
    }

    public void addFinalizeBlock(Block b){
      finalizeBlockList.add(b);
    }

    public XobjList getParamIdList() {
      return paramIdList;
    }

    public void addParamId(Ident id) {
      paramIdList.add(id);
    }

    XobjList getLocalIdList() {
      return localIdList;
    }

    public void addLocalId(Ident id) {
      localIdList.add(id);
    }
  }

  //
  // make kernel functions executed in GPU
  //
  XobjectDef makeDeviceKernelDef(String deviceKernelName, List<Ident> outerIdList, List<Block> kernelBody) {
    /* make deviceKernelBody */
    DeviceKernelBuildInfo kernelBuildInfo = new DeviceKernelBuildInfo();

    /*
    BlockPrintWriter bprinter = new BlockPrintWriter(System.out);
    for(Block b : kernelBody) {
      bprinter.print(b);
    }
    */

    //make params
    //add paramId from outerId
    for (Ident id : outerIdList) {
      // System.out.println("makeDeviceKernelDef() [id = " + id + "] : " + id.Type().isGlobal());
      // if (ACC.device.getUseReadOnlyDataCache() && _readOnlyOuterIdSet.contains(id) && (id.Type().isArray() || id.Type().isPointer())) {
      if ((id.Type().isArray() || id.Type().isPointer())) { // test
        //System.out.println("make const id="+id);
        Xtype constParamType = makeConstRestrictVoidType();
	constParamType.setIsGlobal(true);
        Ident constParamId = Ident.Param("_ACC_const_" + id.getName(), constParamType);

        ACCvar accvar = _kernelInfo.findACCvar(id.getSym());

        Xtype arrayPtrType = accvar.getElementType();

        for(int i = 0; i < accvar.getDim(); i++) {
          arrayPtrType = Xtype.Pointer(arrayPtrType);
        }

        arrayPtrType.setIsGlobal(true);
        Ident localId = Ident.Local(id.getName(), arrayPtrType);
        globalArrayList.add(localId);

        Xobject initialize = Xcons.Set(localId.Ref(), Xcons.Cast(arrayPtrType,  constParamId.Ref()));

        // System.out.println("makeDeviceKernelDef() [initialize = " + initialize. toString() + "]");

        kernelBuildInfo.addParamId(constParamId);


        if(accvar != null && accvar.isFirstprivate())
          localId.setProp(ACCgpuDecompiler.GPU_STORAGE_SHARED, true);
        kernelBuildInfo.addLocalId(localId);
        kernelBuildInfo.addInitBlock(Bcons.Statement(initialize));
      } else {
        ACCvar accvar = _kernelInfo.findACCvar(id.getSym());


        Block parent = _pb.getParentBlock();
        while(parent != null && (parent.Opcode() != Xcode.ACC_PRAGMA || ACCpragma.valueOf(((PragmaBlock)parent).getPragma()) != ACCpragma.DATA)) {
          parent = parent.getParentBlock();
        }

        if(parent == null) {
          ACC.fatal("makeDeviceKernelDef() [failed at makeDeviceKernelDef() (no data block)");
        }

        AccData d_directive = (AccData) parent.getProp(AccData.prop);

        if(d_directive == null) {
          ACC.fatal("makeDeviceKernelDef() [failed at makeDeviceKernelDef() (no data directive)");
        }

        AccInformation dinfo = d_directive.getInfo();

        if(id.Type().getKind() == Xtype.BASIC && accvar.copiesDtoH()) { // test
          Ident paramId = makeParamId(id);
          Ident localId = Ident.Local(id.getName(), id.Type());

          kernelBuildInfo.addParamId(paramId);
          kernelBuildInfo.addLocalId(localId);

          Xobject initialize = Xcons.Set(localId.Ref(), Xcons.PointerRef(paramId.Ref()));
          Xobject finalize = Xcons.Set(Xcons.PointerRef(paramId.Ref()), localId.Ref());

          kernelBuildInfo.addInitBlock(Bcons.Statement(initialize));
          if(fpgaKernelNum == 0) {
            kernelBuildInfo.addFinalizeBlock(Bcons.Statement(finalize));
          }
        } else {
          Ident localId = makeParamId_new(id);

          if (accvar != null) {
            // System.out.println("makeDeviceKernelDef() [accvar pattern 2 = " + accvar.getName() + "]");
          }

          if(accvar != null && accvar.isFirstprivate())
            localId.setProp(ACCgpuDecompiler.GPU_STORAGE_SHARED, true);
          kernelBuildInfo.addParamId(localId);
        }
      }
    }

    //make mainBody
    Block deviceKernelMainBlock = makeCoreBlock(kernelBody, kernelBuildInfo);

    //add private varId only if "parallel"
    if (_kernelInfo.getPragma() == ACCpragma.PARALLEL) {
      List<ACCvar> varList = _kernelInfo.getACCvarList();
      for (ACCvar var : varList) {
        if (var.isPrivate()) {
          Ident privateId = Ident.Local(var.getName(), var.getId().Type());
          privateId.setProp(ACCgpuDecompiler.GPU_STORAGE_SHARED, true);
          kernelBuildInfo.addLocalId(privateId);
        }
      }
    }

    //if extern_sm is used, add extern_sm_id & extern_sm_offset_id
    if (sharedMemory.isUsed()) {
      kernelBuildInfo.addLocalId(sharedMemory.externSmId);
      kernelBuildInfo.addLocalId(sharedMemory.smOffsetId);
    }

    //if block reduction is used
    for(Xobject x : reductionManager.getBlockReductionLocalIds()){

      // System.out.println("makeDeviceKernelDef() [block reduction x = " + x.toString() + "]");

      kernelBuildInfo.addLocalId((Ident) x);
    }

    if (reductionManager.hasUsingTmpReduction()) {
      for(Xobject x : reductionManager.getBlockReductionParamIds()){
        kernelBuildInfo.addParamId((Ident)x);
      }
      allocList.add(Xcons.List(reductionManager.tempPtr, Xcons.IntConstant(0), reductionManager.totalElementSize));
    }

    if (_globalMemoryStack.isUsed()){
      kernelBuildInfo.addParamId(_globalMemoryStack.getBaseId());
      kernelBuildInfo.addLocalId(_globalMemoryStack.getPosId());
      kernelBuildInfo.addInitBlock(_globalMemoryStack.makeInitFunc());
      allocList.add(Xcons.List(_globalMemoryStack.getBaseId(), Xcons.IntConstant(1024 /*temporal value*/), Xcons.IntConstant(0)));
    }

    //FIXME add extern_sm init func
    if (sharedMemory.isUsed()) {
      kernelBuildInfo.addInitBlock(sharedMemory.makeInitFunc()); //deviceKernelBody.add(sharedMemory.makeInitFunc());
    }
    kernelBuildInfo.addInitBlock(reductionManager.makeLocalVarInitFuncs()); //deviceKernelBody.add(reductionManager.makeLocalVarInitFuncs());

    kernelBuildInfo.addInitBlock(reductionManager.makeReduceSetFuncs_CL());
    kernelBuildInfo.addFinalizeBlock(reductionManager.makeReduceAndFinalizeFuncs());
    //deviceKernelBody.add(reductionManager.makeReduceAndFinalizeFuncs());

    BlockList result = Bcons.emptyBody(kernelBuildInfo.getLocalIdList(), null);
    for(Block b : kernelBuildInfo.getInitBlockList()){

      // System.out.println("makeDeviceKernelDef() [init block b = " + b.toString() + "]");

      result.add(b);
    }
    result.add(deviceKernelMainBlock);
    for(Block b : kernelBuildInfo.getFinalizeBlockList()){
      result.add(b);
    }

    XobjList deviceKernelParamIds = kernelBuildInfo.getParamIdList();

    Block resultBlock = Bcons.COMPOUND(result);

    rewriteReferenceType(resultBlock, deviceKernelParamIds);

    Ident deviceKernelId = _decl.getEnvDevice().declGlobalIdent(deviceKernelName, Xtype.Function(Xtype.voidType));
    ((FunctionType) deviceKernelId.Type()).setFuncParamIdList(deviceKernelParamIds);

    // make pointer paramter in kernel function "__global" for OpenCL
    for(Xobject x : deviceKernelParamIds){
      Ident id = (Ident) x;
      if(id.Type().isPointer() || id.Type().isArray()) id.Type().setIsGlobal(true);
    }

    globalArrayList.clear();

    return XobjectDef.Func(deviceKernelId, deviceKernelParamIds, null, resultBlock.toXobject());
  }

  void rewriteReferenceType(Block b, XobjList paramIds) {
    BasicBlockExprIterator iter = new BasicBlockExprIterator(b);
    for (iter.init(); !iter.end(); iter.next()) {
      Xobject expr = iter.getExpr();
      topdownXobjectIterator exprIter = new topdownXobjectIterator(expr);
      for (exprIter.init(); !exprIter.end(); exprIter.next()) {
        Xobject x = exprIter.getXobject();
        // System.out.println("rewriteReferenceType() [x = " + x.toString() + "]");
        switch (x.Opcode()) {
        case VAR: {
          String varName = x.getName();
          if (varName.startsWith("_ACC_")) break;

          // break if the declaration exists in the DeviceKernelBlock
          if (iter.getBasicBlock().getParent().findVarIdent(varName) != null) break;

          // break if the ident doesn't exist in the parameter list
          Ident id = ACCutil.getIdent(paramIds, varName);
          if (id == null) break;

          // break if type is same
          if (x.Type().equals(id.Type())) break;

          if (id.Type().equals(Xtype.Pointer(x.Type()))) {
            Xobject newXobj = Xcons.PointerRef(id.Ref());
            exprIter.setXobject(newXobj);
          } else {
            ACC.fatal("type mismatch");
          }
        }
          break;
        case VAR_ADDR:
          // need to implement
          {
            String varName = x.getName();
            if (varName.startsWith("_ACC_")) break;

            // break if the declaration exists in the DeviceKernelBlock
            if (iter.getBasicBlock().getParent().findVarIdent(varName) != null) break;

            // break if the ident doesn't exist in the parameter list
            Ident id = ACCutil.getIdent(paramIds, varName);
            if (id == null) break;

            if (!x.Type().equals(Xtype.Pointer(id.Type()))) {
              if (x.Type().equals(id.Type())) {
                Xobject newXobj = id.Ref();
                exprIter.setXobject(newXobj);
              } else {
                ACC.fatal("type mismatch");
              }
            }
          }
          break;
        default:
        }
      }
    }
  }

  Block makeCoreBlock(Block b, DeviceKernelBuildInfo deviceKernelBuildInfo) {
    Set<ACCpragma> outerParallelisms = AccLoop.getOuterParallelism(b);
    // System.out.println("makeCoreBlock() [b.Opcode() = " + b.Opcode().toXcodeString() + "]");
    switch (b.Opcode()) {
    case FOR_STATEMENT:
      return makeCoreBlockForStatement((CforBlock) b, deviceKernelBuildInfo);
    case COMPOUND_STATEMENT:
      return makeCoreBlock(b.getBody(), deviceKernelBuildInfo);
    case ACC_PRAGMA: {
      PragmaBlock pb = (PragmaBlock)b;
      ACCpragma pragma = ACCpragma.valueOf(pb.getPragma());
      // System.out.println("makeCoreBlock() [ACCpragma pragma = " + pragma.getName() + "]");
      if(pragma == ACCpragma.ATOMIC) {
        AccAtomic atomicDirective = (AccAtomic)b.getProp(AccDirective.prop);
        try {
          return atomicDirective.makeAtomicBlock();
        } catch (ACCexception exception) {
          exception.printStackTrace();
          ACC.fatal("failed at atomic");
        }
      } else if(pragma == ACCpragma.BRAM) {
        BlockList resultBlock = Bcons.emptyBody();
        BlockList bramDeclBlock = Bcons.emptyBody();
        BlockList bramInitBlock = Bcons.emptyBody();
        BlockList bramInnerBlock = Bcons.emptyBody();
        BlockList bramFinBlock = Bcons.emptyBody();

        if(!onBramList.isEmpty()) {
          ACC.fatal("makeCoreBlock() [failed at bram]");
        }

        // System.out.println("makeCoreBlock() [found BRAM]");
        Block parent = pb.getParentBlock();
        while(parent != null && (parent.Opcode() != Xcode.ACC_PRAGMA || ACCpragma.valueOf(((PragmaBlock)parent).getPragma()) != ACCpragma.DATA)) {
          parent = parent.getParentBlock();
        }

        if(parent == null) {
          ACC.fatal("makeCoreBlock() [failed at bram (no data block)");
        }

        AccData d_directive = (AccData) parent.getProp(AccData.prop);

        if(d_directive == null) {
          ACC.fatal("makeCoreBlock() [failed at bram (no data directive)");
        }

        AccInformation dinfo = d_directive.getInfo();
        AccData b_directive = (AccBram) pb.getProp(AccBram.prop);

        if(b_directive == null) {
          ACC.fatal("makeCoreBlock() [failed at bram (no bram directive)");
        }

        AccInformation binfo = b_directive.getInfo();
        VarListClause alignClause = (VarListClause)binfo.findClause(ACCpragma.ALIGN);
        VarListClause divideClause = (VarListClause)binfo.findClause(ACCpragma.DIVIDE);
        VarListClause shadowClause = (VarListClause)binfo.findClause(ACCpragma.SHADOW);
        VarListClause indexClause = (VarListClause)binfo.findClause(ACCpragma.INDEX);
        VarListClause placeClause = (VarListClause)binfo.findClause(ACCpragma.PLACE);
        onBramIds.clear();
        globalIds.clear();

        if(alignClause != null) {
          for(ACCvar var : alignClause.getVarList()) {
            // System.out.println("makeCoreBlock() [var = " + var.getName() + ", copyin = " + dinfo.findACCvar(var.getName()).copiesHtoD() + ", copyout = " + dinfo.findACCvar(var.getName()).copiesDtoH() + "]");

            boolean isFixed = true;
            Ident globalId = findGlobalArray(var.getName());
            ArrayList<Integer> lengthList = new ArrayList<Integer>();

            for(Xobject x : var.getSubscripts()) {
              Xobject xr = x.right();
              if(!isCalculatable(xr)) {
                isFixed = false;
              } else {
                lengthList.add(0, calcXobject(xr));
              }
            }

            if(isFixed && globalId != null) {
              Ident id = var.getId();

              int totalLength;
              int partLength;
              int remainLength;
              int localLength;
              int partOffset;
              int dim;

              if(fpgaKernelNum == 0) {
                totalLength = lengthList.get(lengthList.size() - 1).intValue();
                partLength = (totalLength - 1) / numKernels + 1;
                remainLength = totalLength - partLength * (numKernels - 1);
                var.setTotalLength(totalLength);
                var.setPartLength(partLength);
                var.setRemainLength(remainLength);
              } else {
                totalLength = var.getTotalLength();
                partLength = var.getPartLength();
                remainLength = var.getRemainLength();
              }
              localLength = partLength;
              partOffset = partLength * fpgaKernelNum;
              var.setPartOffset(partOffset);
              dim = var.getDim();

              if(fpgaKernelNum == numKernels - 1) {
                localLength = remainLength;
              }
              var.setLocalLength(localLength);

              Xtype arrayPtrType = Xtype.Array(id.Type().getRef(), totalLength);
              Ident localId = bramDeclBlock.declLocalIdent("_ACC_BRAM_ALIGN_" + id.getName(), arrayPtrType);

              for(int i = bramIterList.size(); i < dim; i++) {
                Ident bramIter = resultBlock.declLocalIdent("_ACC_BRAM_ITER_" + i, Xtype.unsignedType);
                bramIterList.add(i, bramIter);
              }

              Xobject initialize;
              Xobject finalize;
              Block initForBlock;
              Block finForBlock;

              initialize = Xcons.Set(makeArrayRef(localId, Xcons.IntConstant(0), bramIterList.subList(0, dim)), Xcons.IntConstant(0));

              if(fpgaKernelNum != 0) {
                initForBlock = Bcons.emptyBlock().add(initialize);

                for(int i = 0; i < dim - 1; i++) {
                  initForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(lengthList.get(i).intValue())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), initForBlock);
                }

                initForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(partOffset)), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(1)), initForBlock);

                if(dinfo.findACCvar(var.getName()).copiesHtoD()) {
                  bramInitBlock.add(initForBlock);
                }
              }

              initialize = elementSet(localId, id, Xcons.IntConstant(partOffset), bramIterList.subList(0, dim));
              finalize = elementSet(id, localId, Xcons.IntConstant(partOffset), bramIterList.subList(0, dim));
              initForBlock = Bcons.emptyBlock().add(initialize);
              finForBlock = Bcons.emptyBlock().add(finalize);

              for(int i = 0; i < dim - 1; i++) {
                initForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(lengthList.get(i).intValue())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), initForBlock);
                finForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(lengthList.get(i).intValue())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), finForBlock);
              }

              initForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(localLength)), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(1)), initForBlock);
              finForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(localLength)), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(1)), finForBlock);

              if(dinfo.findACCvar(var.getName()).copiesHtoD()) {
                bramInitBlock.add(initForBlock);
              }
              if(dinfo.findACCvar(var.getName()).copiesDtoH()) {
                bramFinBlock.add(finForBlock);
              }

              initialize = Xcons.Set(makeArrayRef(localId, Xcons.IntConstant(partOffset + localLength), bramIterList.subList(0, dim)), Xcons.IntConstant(0));

              if(fpgaKernelNum != numKernels - 1) {
                initForBlock = Bcons.emptyBlock().add(initialize);

                for(int i = 0; i < dim - 1; i++) {
                  initForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(lengthList.get(i).intValue())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), initForBlock);
                }

                initForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(totalLength - (partOffset + localLength))), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(1)), initForBlock);

                if(dinfo.findACCvar(var.getName()).copiesHtoD()) {
                  bramInitBlock.add(initForBlock);
                }
              }

              onBramIds.add(localId);
              globalIds.add(globalId);
              onBramList.add(var);
            } else {
              System.err.println("makeCoreBlock() [BRAM skipped]");
            }
          }
        }

        if(divideClause != null) {
          for(ACCvar var : divideClause.getVarList()) {
            // System.out.println("makeCoreBlock() [var = " + var.getName() + ", copyin = " + dinfo.findACCvar(var.getName()).copiesHtoD() + ", copyout = " + dinfo.findACCvar(var.getName()).copiesDtoH() + "]");

            boolean isFixed = true;
            Ident globalId = findGlobalArray(var.getName());
            ArrayList<Integer> lengthList = new ArrayList<Integer>();

            for(Xobject x : var.getSubscripts()) {
              Xobject xr = x.right();
              if(!isCalculatable(xr)) {
                isFixed = false;
              } else {
                lengthList.add(0, calcXobject(xr));
              }
            }

            if(isFixed && globalId != null) {
              Ident id = var.getId();

              int totalLength;
              int partLength;
              int remainLength;
              int localLength;
              int partOffset;
              int dim;

              if(fpgaKernelNum == 0) {
                totalLength = lengthList.get(lengthList.size() - 1).intValue();
                partLength = (totalLength - 1) / numKernels + 1;
                remainLength = totalLength - partLength * (numKernels - 1);
                var.setTotalLength(totalLength);
                var.setPartLength(partLength);
                var.setRemainLength(remainLength);
              } else {
                totalLength = var.getTotalLength();
                partLength = var.getPartLength();
                remainLength = var.getRemainLength();
              }
              localLength = partLength;
              partOffset = partLength * fpgaKernelNum;
              var.setPartOffset(partOffset);
              dim = var.getDim();

              if(fpgaKernelNum == numKernels - 1) {
                localLength = remainLength;
              }
              var.setLocalLength(localLength);

              Xtype arrayPtrType = Xtype.Array(id.Type().getRef(), localLength);
              // System.out.println("makeCoreBlock() [arrayPtrType = " + arrayPtrType + "]");
              Ident localId = bramDeclBlock.declLocalIdent("_ACC_BRAM_DIVIDE_" + id.getName(), arrayPtrType);

              for(int i = bramIterList.size(); i < dim; i++) {
                Ident bramIter = resultBlock.declLocalIdent("_ACC_BRAM_ITER_" + i, Xtype.unsignedType);
                bramIterList.add(i, bramIter);
              }

              Xobject initialize;
              Xobject finalize;
              Block initForBlock;
              Block finForBlock;

              initialize = elementSet(localId, id, Xcons.IntConstant(0), Xcons.IntConstant(partOffset), bramIterList.subList(0, dim));
              finalize = elementSet(id, localId, Xcons.IntConstant(partOffset), Xcons.IntConstant(0), bramIterList.subList(0, dim));
              initForBlock = Bcons.emptyBlock().add(initialize);
              finForBlock = Bcons.emptyBlock().add(finalize);

              for(int i = 0; i < dim - 1; i++) {
                initForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(lengthList.get(i).intValue())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), initForBlock);
                finForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(lengthList.get(i).intValue())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), finForBlock);
              }

              initForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(localLength)), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(1)), initForBlock);
              finForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(localLength)), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(1)), finForBlock);

              if(dinfo.findACCvar(var.getName()).copiesHtoD()) {
                bramInitBlock.add(initForBlock);
              }
              if(dinfo.findACCvar(var.getName()).copiesDtoH()) {
                bramFinBlock.add(finForBlock);
              }

              onBramIds.add(localId);
              globalIds.add(globalId);
              onBramList.add(var);
            } else {
              System.err.println("makeCoreBlock() [not fixed, BRAM skipped]");
            }
          }
        }

        if(shadowClause != null) {
          for(ACCvar var : shadowClause.getVarList()) {
            // System.out.println("makeCoreBlock() [var = " + var.getName() + "[" + var.getFrontOffsetXobject() + ":" + var.getBackOffsetXobject()  + "], copyin = " + dinfo.findACCvar(var.getName()).copiesHtoD() + ", copyout = " + dinfo.findACCvar(var.getName()).copiesDtoH() + "]");

            boolean isFixed = true;
            Ident globalId = findGlobalArray(var.getName());
            ArrayList<Integer> lengthList = new ArrayList<Integer>();
            Xobject frontXobject = var.getFrontOffsetXobject();
            Xobject backXobject = var.getBackOffsetXobject();

            for(Xobject x : var.getSubscripts()) {
              Xobject xr = x.right();
              if(!isCalculatable(xr)) {
                isFixed = false;
              } else {
                lengthList.add(0, calcXobject(xr));
              }
            }

            if(isFixed && globalId != null && isCalculatable(frontXobject) && isCalculatable(backXobject)) {
              Ident id = var.getId();

              int totalLength;
              int partLength;
              int remainLength;
              int localLength;
              int partOffset;
              int frontOffset;
              int backOffset;
              int dim;

              if(fpgaKernelNum == 0) {
                totalLength = lengthList.get(lengthList.size() - 1).intValue();
                partLength = (totalLength - 1) / numKernels + 1;
                remainLength = totalLength - partLength * (numKernels - 1);
                frontOffset = calcXobject(frontXobject);
                backOffset = calcXobject(backXobject);
                var.setTotalLength(totalLength);
                var.setPartLength(partLength);
                var.setRemainLength(remainLength);
                var.setFrontOffset(frontOffset);
                var.setBackOffset(backOffset);
              } else {
                totalLength = var.getTotalLength();
                partLength = var.getPartLength();
                remainLength = var.getRemainLength();
                frontOffset = var.getFrontOffset();
                backOffset = var.getBackOffset();
              }
              partOffset = partLength * fpgaKernelNum;
              var.setPartOffset(partOffset);
              localLength = partLength;
              dim = var.getDim();

              if(fpgaKernelNum == 0) {
                frontOffset = 0;
              }
              if(fpgaKernelNum == numKernels - 1) {
                localLength = remainLength;
                backOffset = 0;
              }
              var.setLocalLength(localLength);

              Xtype arrayPtrType = Xtype.Array(id.Type().getRef(), frontOffset + localLength + backOffset);
              // System.out.println("makeCoreBlock() [arrayPtrType = " + arrayPtrType + "]");
              Ident localId = bramDeclBlock.declLocalIdent("_ACC_BRAM_SHADOW_" + id.getName(), arrayPtrType);

              for(int i = bramIterList.size(); i < dim; i++) {
                Ident bramIter = resultBlock.declLocalIdent("_ACC_BRAM_ITER_" + i, Xtype.unsignedType);
                bramIterList.add(i, bramIter);
              }

              Xobject initialize;
              Xobject finalize;
              Block initForBlock;
              Block finForBlock;

              initialize = Xcons.Set(makeArrayRef(localId, Xcons.IntConstant(0), bramIterList.subList(0, dim)), Xcons.IntConstant(0));

              if(frontOffset != 0) {
                initForBlock = Bcons.emptyBlock().add(initialize);

                for(int i = 0; i < dim - 1; i++) {
                  initForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(lengthList.get(i).intValue())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), initForBlock);
                }
                initForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(frontOffset)), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(1)), initForBlock);

                if(dinfo.findACCvar(var.getName()).copiesHtoD()) {
                  bramInitBlock.add(initForBlock);
                }
              }

              initialize = elementSet(localId, id, Xcons.IntConstant(frontOffset), Xcons.IntConstant(partOffset), bramIterList.subList(0, dim));
              finalize = elementSet(id, localId, Xcons.IntConstant(partOffset), Xcons.IntConstant(frontOffset), bramIterList.subList(0, dim));
              initForBlock = Bcons.emptyBlock().add(initialize);
              finForBlock = Bcons.emptyBlock().add(finalize);

              for(int i = 0; i < dim - 1; i++) {
                initForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(lengthList.get(i).intValue())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), initForBlock);
                finForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(lengthList.get(i).intValue())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), finForBlock);
              }

              initForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(localLength)), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(1)), initForBlock);
              finForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(localLength)), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(1)), finForBlock);

              if(dinfo.findACCvar(var.getName()).copiesHtoD()) {
                bramInitBlock.add(initForBlock);
              }
              if(dinfo.findACCvar(var.getName()).copiesDtoH()) {
                bramFinBlock.add(finForBlock);
              }

              initialize = Xcons.Set(makeArrayRef(localId, Xcons.IntConstant(frontOffset + localLength), bramIterList.subList(0, dim)), Xcons.IntConstant(0));

              if(backOffset != 0) {
                initForBlock = Bcons.emptyBlock().add(initialize);

                for(int i = 0; i < dim - 1; i++) {
                  initForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(lengthList.get(i).intValue())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), initForBlock);
                }
                initForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(backOffset)), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(1)), initForBlock);

                if(dinfo.findACCvar(var.getName()).copiesHtoD()) {
                  bramInitBlock.add(initForBlock);
                }
              }

              onBramIds.add(localId);
              globalIds.add(globalId);
              onBramList.add(var);
            } else {
              System.err.println("makeCoreBlock() [not fixed, BRAM skipped]");
            }
          }
        }

        if(indexClause != null) {
          for(ACCvar var : indexClause.getVarList()) {
            // System.out.println("makeCoreBlock() [var = " + var.getName() + "[" + var.getIndexOffsetXobject() + ":" + var.getIndexLengthXobject()  + "], copyin = " + dinfo.findACCvar(var.getName()).copiesHtoD() + ", copyout = " + dinfo.findACCvar(var.getName()).copiesDtoH() + "]");
            if(dinfo.findACCvar(var.getName()).copiesDtoH() || !dinfo.findACCvar(var.getName()).copiesHtoD()) {
              System.err.println("makeCoreBlock() [index supports copyin only, BRAM skipped]");
              continue;
            }

            boolean isFixed = true;
            Ident globalId = findGlobalArray(var.getName());
            ArrayList<Integer> lengthList = new ArrayList<Integer>();
            Xobject offsetXobject = var.getIndexOffsetXobject();
            Xobject lengthXobject = var.getIndexLengthXobject();

            for(Xobject x : var.getSubscripts()) {
              Xobject xr = x.right();
              if(!isCalculatable(xr)) {
                isFixed = false;
              } else {
                lengthList.add(0, calcXobject(xr));
              }
            }

            if(isFixed && globalId != null && isCalculatable(offsetXobject) && isCalculatable(lengthXobject)) {
              Ident id = var.getId();

              int totalLength;
              int partLength;
              int remainLength;
              int localLength;
              int partOffset;
              int indexOffset;
              int indexLength;
              int localIndex;
              int lengthOffset;
              int dim;

              if(fpgaKernelNum == 0) {
                indexOffset = calcXobject(offsetXobject);
                indexLength = calcXobject(lengthXobject);
                totalLength = lengthList.get(lengthList.size() - 1).intValue();
                partLength = (totalLength - 1 - indexOffset) / numKernels + 1;
                remainLength = totalLength - partLength * (numKernels - 1) - indexOffset;
                var.setTotalLength(totalLength);
                var.setPartLength(partLength);
                var.setRemainLength(remainLength);
                var.setIndexOffset(indexOffset);
                var.setIndexLength(indexLength);
              } else {
                totalLength = var.getTotalLength();
                partLength = var.getPartLength();
                remainLength = var.getRemainLength();
                indexOffset = var.getIndexOffset();
                indexLength = var.getIndexLength();
              }
              partOffset = partLength * fpgaKernelNum;
              var.setPartOffset(partOffset);
              localLength = partLength;
              localIndex = (indexLength - 1) / numKernels + 1;
              lengthOffset = localIndex * fpgaKernelNum;

              dim = var.getDim();

              if(fpgaKernelNum == numKernels - 1) {
                localLength = remainLength;
                localIndex = indexLength - lengthOffset;
              }
              var.setLocalLength(localLength);

              Xtype arrayPtrType = Xtype.Array(id.Type().getRef(), totalLength);
              // System.out.println("makeCoreBlock() [arrayPtrType = " + arrayPtrType + "]");
              Ident localId = bramDeclBlock.declLocalIdent("_ACC_BRAM_INDEX_" + id.getName(), arrayPtrType);

              for(int i = bramIterList.size(); i < dim; i++) {
                Ident bramIter = resultBlock.declLocalIdent("_ACC_BRAM_ITER_" + i, Xtype.unsignedType);
                bramIterList.add(i, bramIter);
              }

              Xobject initialize0;
              Xobject initialize1;
              Xobject initialize2;
              Xobject setL;
              Xobject setR;
              Block initForBlock;

              initialize0 = Xcons.Set(makeArrayRef(localId, Xcons.IntConstant(0), bramIterList.subList(0, dim)), Xcons.IntConstant(0));
              initialize1 = Xcons.Set(makeArrayRef(localId, Xcons.IntConstant(0), bramIterList.subList(0, dim)), Xcons.binaryOp(Xcode.MINUS_EXPR, makeArrayRef(globalId, Xcons.IntConstant(0), bramIterList.subList(0, dim)), Xcons.IntConstant(lengthOffset)));
              initialize2 = Xcons.Set(makeArrayRef(localId, Xcons.IntConstant(0), bramIterList.subList(0, dim)), Xcons.IntConstant(localIndex));

              // System.out.println("initialize0 = " + initialize0);
              // System.out.println("initialize1 = " + initialize1);
              // System.out.println("initialize2 = " + initialize2);

              initForBlock = Bcons.IF(Xcons.List(Xcode.LOG_LT_EXPR, var.getElementType(), makeArrayRef(globalId, Xcons.IntConstant(0), bramIterList.subList(0, dim)), Xcons.IntConstant(lengthOffset + localIndex)), Bcons.Statement(initialize1), Bcons.Statement(initialize2));

              initForBlock = Bcons.IF(Xcons.List(Xcode.LOG_LT_EXPR, var.getElementType(), makeArrayRef(globalId, Xcons.IntConstant(0), bramIterList.subList(0, dim)), Xcons.IntConstant(lengthOffset)), Bcons.Statement(initialize0), Bcons.COMPOUND(Bcons.blockList(initForBlock)));

              if(numKernels == 1) {
                initForBlock = Bcons.Statement(elementSet(localId, globalId, Xcons.IntConstant(0), bramIterList));
              }

              for(int i = 0; i < dim; i++) {
                initForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(lengthList.get(i).intValue())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), initForBlock);
              }
              bramInitBlock.add(initForBlock);

              /*
              if(fpgaKernelNum != 0) {
                initForBlock = Bcons.emptyBlock().add(initialize);

                for(int i = 0; i < dim - 1; i++) {
                  initForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(lengthList.get(i).intValue())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), initForBlock);
                }
                initForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(partOffset)), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(1)), initForBlock);

                bramInitBlock.add(initForBlock);
              }

              setL = makeArrayRef(localId, Xcons.IntConstant(0), bramIterList.subList(0, dim));
              setR = Xcons.binaryOp(Xcode.MINUS_EXPR, makeArrayRef(id, Xcons.IntConstant(0), bramIterList.subList(0, dim)), Xcons.IntConstant(lengthOffset));
              initialize = Xcons.Set(setL, setR);
              initForBlock = Bcons.emptyBlock().add(initialize);

              for(int i = 0; i < dim - 1; i++) {
                initForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(lengthList.get(i).intValue())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), initForBlock);
              }
              initForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(partOffset)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(partOffset + localLength + indexOffset)), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(1)), initForBlock);

              bramInitBlock.add(initForBlock);

              initialize = Xcons.Set(makeArrayRef(localId, Xcons.IntConstant(0), bramIterList.subList(0, dim)), Xcons.IntConstant(lastIndex));

              if(fpgaKernelNum != numKernels - 1 && indexOffset != 0) {
                initForBlock = Bcons.emptyBlock().add(initialize);

                for(int i = 0; i < dim - 1; i++) {
                  initForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(lengthList.get(i).intValue())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), initForBlock);
                }
                initForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(partOffset + localLength + indexOffset)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(totalLength)), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(1)), initForBlock);

                bramInitBlock.add(initForBlock);
              }
              */

              onBramIds.add(localId);
              globalIds.add(globalId);
              onBramList.add(var);
            } else {
              System.err.println("makeCoreBlock() [not fixed, BRAM skipped]");
            }
          }
        }

        if(placeClause != null) {
          for(ACCvar var : placeClause.getVarList()) {
            // System.out.println("makeCoreBlock() [var = " + var.getName() + ", copyin = " + dinfo.findACCvar(var.getName()).copiesHtoD() + ", copyout = " + dinfo.findACCvar(var.getName()).copiesDtoH() + "]");
            if(dinfo.findACCvar(var.getName()).copiesDtoH()) {
              System.err.println("makeCoreBlock() [place does not support copy & copyout, BRAM skipped]");
              continue;
            }

            boolean isFixed = true;
            Ident globalId = findGlobalArray(var.getName());
            ArrayList<Integer> lengthList = new ArrayList<Integer>();

            for(Xobject x : var.getSubscripts()) {
              Xobject xr = x.right();
              if(!isCalculatable(xr)) {
                isFixed = false;
              } else {
                lengthList.add(0, calcXobject(xr));
              }
            }

            if(isFixed && globalId != null) {
              Ident id = var.getId();

              int totalLength;
              int partLength;
              int remainLength;
              int partOffset;
              int dim;

              if(fpgaKernelNum == 0) {
                totalLength = lengthList.get(lengthList.size() - 1).intValue();
                partLength = totalLength;
                remainLength = totalLength;
                var.setTotalLength(totalLength);
                var.setPartLength(partLength);
                var.setRemainLength(remainLength);
              } else {
                totalLength = var.getTotalLength();
                partLength = var.getPartLength();
                remainLength = var.getRemainLength();
              }
              partOffset = 0;
              var.setPartOffset(partOffset);
              dim = var.getDim();

              Xtype arrayPtrType = Xtype.Array(id.Type().getRef(), totalLength);
              // System.out.println("makeCoreBlock() [arrayPtrType = " + arrayPtrType + "]");
              Ident localId = bramDeclBlock.declLocalIdent("_ACC_BRAM_PLACE_" + id.getName(), arrayPtrType);

              for(int i = bramIterList.size(); i < dim; i++) {
                Ident bramIter = resultBlock.declLocalIdent("_ACC_BRAM_ITER_" + i, Xtype.unsignedType);
                bramIterList.add(i, bramIter);
              }

              Xobject initialize;
              Block initForBlock;

              initialize = elementSet(localId, id, Xcons.IntConstant(0), bramIterList.subList(0, dim));
              initForBlock = Bcons.emptyBlock().add(initialize);

              for(int i = 0; i < dim - 1; i++) {
                initForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(lengthList.get(i).intValue())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), initForBlock);
              }

              initForBlock = Bcons.FOR(Xcons.Set(bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(totalLength)), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(1)), initForBlock);

              if(dinfo.findACCvar(var.getName()).copiesHtoD()) {
                bramInitBlock.add(initForBlock);
              }

              onBramIds.add(localId);
              globalIds.add(globalId);
              onBramList.add(var);
            } else {
              System.err.println("makeCoreBlock() [not fixed, BRAM skipped]");
            }
          }
        }

        Block body = makeCoreBlock(b.getBody(), deviceKernelBuildInfo);

        for(int i = 0; i < onBramIds.size(); i++) {
          // System.out.println("globalIds.get(" +  i + ") = " + globalIds.get(i).getName());
          // System.out.println("onBramIds.get(" +  i + ") = " + onBramIds.get(i).getName());
          replaceArrayPointer(body, globalIds.get(i), onBramIds.get(i));
        }

        bramDeclBlock.add(Bcons.COMPOUND(bramInitBlock));
        bramDeclBlock.add(body);
        bramDeclBlock.add(Bcons.COMPOUND(bramFinBlock));
        resultBlock.add(Bcons.COMPOUND(bramDeclBlock));

        onBramList.clear();
        return Bcons.COMPOUND(resultBlock);
      } else if(pragma == ACCpragma.BCAST) {
        // System.out.println("makeCoreBlock() [found Bcast]");
        BlockList body = Bcons.emptyBody();

        Block parent = pb.getParentBlock();
        while(parent != null && (parent.Opcode() != Xcode.ACC_PRAGMA || ACCpragma.valueOf(((PragmaBlock)parent).getPragma()) != ACCpragma.BRAM)) {
          parent = parent.getParentBlock();
        }

        if(parent == null) {
          ACC.fatal("makeCoreBlock() [failed at bcast (no bram block)");
        }

        AccBram b_directive = (AccBram) parent.getProp(AccBram.prop);

        if(b_directive == null) {
          ACC.fatal("makeCoreBlock() [failed at bcast (no bram directive)");
        }

        AccInformation binfo = b_directive.getInfo();
        AccBcast directive = (AccBcast) pb.getProp(AccBcast.prop);

        if(directive == null) {
          ACC.fatal("makeCoreBlock() [failed at bcast (no bcast directive)");
        }

        AccInformation info = directive.getInfo();
        VarListClause bcastClause = (VarListClause)info.findClause(ACCpragma.BCAST);

        if(bcastClause.getVarList().size() != 1) {
          ACC.fatal("makeCoreBlock() [failed at bcast (invalid list size)");
        }

        ACCvar var = bcastClause.getVarList().get(0);
        ACCvar bvar = binfo.findACCvar(var.getName());


        if(bvar != null) {
          // System.out.println("makeCoreBlock() [array " + bvar.getName() + " bcast]");

          if(findBramPointerRef(bvar.getId().Ref()) == null) {
            System.err.println("makeCoreBlock() [not on bram, bcast skipped]");
            return Bcons.COMPOUND(body);
          }

          if(!bvar.isAlign() && !bvar.isPlace()) {
            System.err.println("makeCoreBlock() [not place, bcast skipped]");
            return Bcons.COMPOUND(body);
          }

          Block commBlock = Bcons.emptyBlock();
          int dim = var.getDim();

          if(fpgaKernelNum == 0) {
            BlockList senders = Bcons.emptyBody();

            for(int i = 1; i < numKernels; i++) {
              Xobject sender = Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_SEND_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), 0, i).Ref(), makeArrayRef(var.getId(), Xcons.IntConstant(0), bramIterList.subList(0, dim))));
              // System.out.println("makeCoreBlock() [sender = " + sender + "]");
              senders.add(sender);
            }
            commBlock = Bcons.COMPOUND(senders);

            for(int i = 0; i < dim; i++) {
              commBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(bvar.getTotalLength())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), commBlock);
            }
          } else {
            Xobject receiver = Xcons.List(Xcons.Set(makeArrayRef(var.getId(), Xcons.IntConstant(0), bramIterList.subList(0, dim)), Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_RECV_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), 0, fpgaKernelNum).Ref()))));
            // System.out.println("makeCoreBlock() [receiver = " + receiver + "]");

            commBlock.add(receiver);

            for(int i = 0; i < dim; i++) {
              commBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(bvar.getTotalLength())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), commBlock);
            }
          }

          body.add(commBlock);
        } else {
          if(var.isArray()) {
            System.err.println("makeCoreBlock() [not on bram, bcast skipped]");
            return Bcons.COMPOUND(body);
          }

          // System.out.println("makeCoreBlock() [variable bcast]");
          if(fpgaKernelNum == 0) {
            for(int i = 1; i < numKernels; i++) {
              body.add(Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_SEND_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), 0, i).Ref(), var.getId().Ref())));
            }
          } else {
            body.add(Xcons.List(Xcons.Set(var.getId().Ref(), Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_RECV_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), 0, fpgaKernelNum).Ref())))));
          }
        }

        return Bcons.COMPOUND(body);
      } else if(pragma == ACCpragma.REFLECT) {
        // System.out.println("makeCoreBlock() [found Reflect]");
        BlockList body = Bcons.emptyBody();

        Block parent = pb.getParentBlock();
        while(parent != null && (parent.Opcode() != Xcode.ACC_PRAGMA || ACCpragma.valueOf(((PragmaBlock)parent).getPragma()) != ACCpragma.BRAM)) {
          parent = parent.getParentBlock();
        }

        if(parent == null) {
          ACC.fatal("makeCoreBlock() [failed at reflect (no bram block)");
        }

        AccBram b_directive = (AccBram) parent.getProp(AccBram.prop);

        if(b_directive == null) {
          ACC.fatal("makeCoreBlock() [failed at reflect (no bram directive)");
        }

        AccInformation binfo = b_directive.getInfo();
        AccReflect directive = (AccReflect) pb.getProp(AccReflect.prop);

        if(directive == null) {
          ACC.fatal("makeCoreBlock() [failed at reflect (no reflect directive)");
        }

        AccInformation info = directive.getInfo();
        VarListClause reflectClause = (VarListClause)info.findClause(ACCpragma.REFLECT);

        if(reflectClause.getVarList().size() != 1) {
          ACC.fatal("makeCoreBlock() [failed at reflect (invalid list size)");
        }

        ACCvar var = reflectClause.getVarList().get(0);
        ACCvar bvar = binfo.findACCvar(var.getName());

        if(bvar != null) {
          // System.out.println("makeCoreBlock() [array " + bvar.getName() + " reflect]");

          if(findBramPointerRef(bvar.getId().Ref()) == null) {
            System.err.println("makeCoreBlock() [not on bram, reflect skipped]");
            return Bcons.COMPOUND(body);
          }

          if(!bvar.isShadow()) {
            System.err.println("makeCoreBlock() [not shadow, reflect skipped]");
            return Bcons.COMPOUND(body);
          }

          int frontOffset = bvar.getFrontOffset();
          int localLength = bvar.getLocalLength();
          int backOffset = bvar.getBackOffset();
          int dim = var.getDim();

          if(fpgaKernelNum != 0) {
            // communication to upper kernel
            BlockList upperList = Bcons.emptyBody();

            if(backOffset > 0) {
              Block upperBlock = Bcons.emptyBlock();

              Xobject sender = Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_SEND_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), fpgaKernelNum, fpgaKernelNum - 1).Ref(), makeArrayRef(var.getId(), Xcons.IntConstant(0), bramIterList.subList(0, dim))));
              // System.out.println("makeCoreBlock() [sender = " + sender + "]");
              upperBlock.add(sender);

              for(int i = 0; i < dim - 1; i++) {
                upperBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(bvar.getTotalLength())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), upperBlock);
              }
              upperBlock = Bcons.FOR(Xcons.Set(bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(frontOffset)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(frontOffset + backOffset)), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(1)), upperBlock);
              upperList.add(upperBlock);
            }

            if(frontOffset > 0) {
              Block upperBlock = Bcons.emptyBlock();
              Xobject receiver = Xcons.List(Xcons.Set(makeArrayRef(var.getId(), Xcons.IntConstant(0), bramIterList.subList(0, dim)), Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_RECV_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), fpgaKernelNum - 1, fpgaKernelNum).Ref()))));
              // System.out.println("makeCoreBlock() [receiver = " + receiver + "]");
              upperBlock.add(receiver);

              for(int i = 0; i < dim - 1; i++) {
                upperBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(bvar.getTotalLength())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), upperBlock);
              }
              upperBlock = Bcons.FOR(Xcons.Set(bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(frontOffset)), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(1)), upperBlock);
              upperList.add(upperBlock);

              body.add(Bcons.COMPOUND(upperList));
            }
          }

          if(fpgaKernelNum != numKernels - 1) {
            // communication to lower kernel
            BlockList lowerList = Bcons.emptyBody();
            int localOffset = frontOffset;

            if(fpgaKernelNum == 0) {
              localOffset = 0;
            }

            if(backOffset > 0) {
              Block lowerBlock = Bcons.emptyBlock();

              Xobject receiver = Xcons.List(Xcons.Set(makeArrayRef(var.getId(), Xcons.IntConstant(0), bramIterList.subList(0, dim)), Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_RECV_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), fpgaKernelNum + 1, fpgaKernelNum).Ref()))));
              // System.out.println("makeCoreBlock() [receiver = " + receiver + "]");
              lowerBlock.add(receiver);

              for(int i = 0; i < dim - 1; i++) {
                lowerBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(bvar.getTotalLength())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), lowerBlock);
              }
              lowerBlock = Bcons.FOR(Xcons.Set(bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(localOffset + localLength)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(localOffset + localLength + backOffset)), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(1)), lowerBlock);
              lowerList.add(lowerBlock);
            }

            if(frontOffset > 0) {
              Block lowerBlock = Bcons.emptyBlock();
              Xobject sender = Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_SEND_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), fpgaKernelNum, fpgaKernelNum + 1).Ref(), makeArrayRef(var.getId(), Xcons.IntConstant(0), bramIterList.subList(0, dim))));
              // System.out.println("makeCoreBlock() [sender = " + sender + "]");
              lowerBlock.add(sender);

              for(int i = 0; i < dim - 1; i++) {
                lowerBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(bvar.getTotalLength())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), lowerBlock);
              }
              lowerBlock = Bcons.FOR(Xcons.Set(bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(localOffset + localLength - frontOffset)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(localOffset + localLength)), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(1)), lowerBlock);
              lowerList.add(lowerBlock);

              body.add(Bcons.COMPOUND(lowerList));
            }
          }
        } else {
          System.err.println("makeCoreBlock() [variable or not on bram, reflect skipped]");
        }

        return Bcons.COMPOUND(body);
      } else if(pragma == ACCpragma.ALLGATHER) {
        // System.out.println("makeCoreBlock() [found Allgather]");
        BlockList body = Bcons.emptyBody();

        Block parent = pb.getParentBlock();
        while(parent != null && (parent.Opcode() != Xcode.ACC_PRAGMA || ACCpragma.valueOf(((PragmaBlock)parent).getPragma()) != ACCpragma.BRAM)) {
          parent = parent.getParentBlock();
        }

        if(parent == null) {
          ACC.fatal("makeCoreBlock() [failed at allgather (no bram block)");
        }

        AccBram b_directive = (AccBram) parent.getProp(AccBram.prop);

        if(b_directive == null) {
          ACC.fatal("makeCoreBlock() [failed at allgather (no bram directive)");
        }

        AccInformation binfo = b_directive.getInfo();
        AccAllgather directive = (AccAllgather) pb.getProp(AccAllgather.prop);

        if(directive == null) {
          ACC.fatal("makeCoreBlock() [failed at allgather (no allgather directive)");
        }

        AccInformation info = directive.getInfo();
        VarListClause allgatherClause = (VarListClause)info.findClause(ACCpragma.ALLGATHER);

        if(allgatherClause.getVarList().size() != 1) {
          ACC.fatal("makeCoreBlock() [failed at allgather (invalid list size)");
        }

        ACCvar var = allgatherClause.getVarList().get(0);
        ACCvar bvar = binfo.findACCvar(var.getName());

        if(bvar != null) {
          // System.out.println("makeCoreBlock() [array " + bvar.getName() + " allgather]");

          if(findBramPointerRef(bvar.getId().Ref()) == null) {
            System.err.println("makeCoreBlock() [not on bram, allgather skipped]");
            return Bcons.COMPOUND(body);
          }

          if(!bvar.isAlign() && !bvar.isPlace()) {
            System.err.println("makeCoreBlock() [not align, allgather skipped]");
            return Bcons.COMPOUND(body);
          }

          BlockList gatherBlock = Bcons.emptyBody();
          BlockList bcastBlock = Bcons.emptyBody();
          int partLength = bvar.getPartLength();
          int partOffset = bvar.getPartOffset();
          int length = partLength;
          int dim = var.getDim();
          if(bvar.isPlace()) {
            partLength = (bvar.getTotalLength() - 1) / numKernels + 1;
          }

          if(fpgaKernelNum == 0) {
            for(int i = 1; i < numKernels; i++) {
              Block commBlock = Bcons.emptyBlock();
              partOffset = partLength * i;
              if(i == numKernels - 1) {
                if(bvar.isAlign()) {
                  length = bvar.getRemainLength();
                } else {
                  length = bvar.getTotalLength() - partOffset;
                }
              }
              Xobject receiver = Xcons.List(Xcons.Set(makeArrayRef(var.getId(), Xcons.IntConstant(0), bramIterList.subList(0, dim)), Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_RECV_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), i, 0).Ref()))));
              // System.out.println("makeCoreBlock() [receiver = " + receiver + "]");
              commBlock.add(receiver);

              for(int j = 0; j < dim - 1; j++) {
                commBlock = Bcons.FOR(Xcons.Set(bramIterList.get(j).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(j).Ref(), Xcons.IntConstant(bvar.getTotalLength())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(j).Ref(), Xcons.IntConstant(1)), commBlock);
              }
              commBlock = Bcons.FOR(Xcons.Set(bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(partOffset)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(partOffset + length)), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(1)), commBlock);
              gatherBlock.add(commBlock);
            }

            length = partLength;
            for(int i = 0; i < numKernels; i++) {
              if(numKernels == 1) {
                break;
              }

              Block commBlock = Bcons.emptyBlock();
              if(i == numKernels - 1) {
                if(bvar.isAlign()) {
                  length = bvar.getRemainLength();
                } else {
                  length = bvar.getTotalLength() - partOffset;
                }
              }
              partOffset = partLength * i;

              for(int j = 1; j < numKernels; j++) {
                if(i == j) {
                  continue;
                }

                Xobject sender = Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_SEND_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), 0, j).Ref(), makeArrayRef(var.getId(), Xcons.IntConstant(0), bramIterList.subList(0, dim))));
                // System.out.println("makeCoreBlock() [sender = " + sender + "]");
                commBlock.add(sender);
              }

              for(int j = 0; j < dim - 1; j++) {
                commBlock = Bcons.FOR(Xcons.Set(bramIterList.get(j).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(j).Ref(), Xcons.IntConstant(bvar.getTotalLength())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(j).Ref(), Xcons.IntConstant(1)), commBlock);
              }
              commBlock = Bcons.FOR(Xcons.Set(bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(partOffset)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(partOffset + length)), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(1)), commBlock);
              bcastBlock.add(commBlock);

              if(numKernels == 2) {
                break;
              }
            }
          } else {
            Block commBlock = Bcons.emptyBlock();
            length = bvar.getLocalLength();
            if(bvar.isPlace()) {
              if(fpgaKernelNum != numKernels - 1) {
                length = partLength;
              } else {
                partOffset = partLength * fpgaKernelNum;
                length = bvar.getTotalLength() - partOffset;
              }
            }

            Xobject sender = Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_SEND_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), fpgaKernelNum, 0).Ref(), makeArrayRef(var.getId(), Xcons.IntConstant(0), bramIterList.subList(0, dim))));
            // System.out.println("makeCoreBlock() [sender = " + sender + "]");
            commBlock.add(sender);

            for(int i = 0; i < dim - 1; i++) {
              commBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(bvar.getTotalLength())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), commBlock);
            }
            commBlock = Bcons.FOR(Xcons.Set(bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(partOffset)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(partOffset + length)), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(1)), commBlock);
            gatherBlock.add(commBlock);

            commBlock = Bcons.emptyBlock();
            Xobject receiver = Xcons.List(Xcons.Set(makeArrayRef(var.getId(), Xcons.IntConstant(0), bramIterList.subList(0, dim)), Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_RECV_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), 0, fpgaKernelNum).Ref()))));
            // System.out.println("makeCoreBlock() [receiver = " + receiver + "]");
            commBlock.add(receiver);

            for(int i = 0; i < dim - 1; i++) {
              commBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(bvar.getTotalLength())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), commBlock);
            }
            commBlock = Bcons.FOR(Xcons.Set(bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(partOffset)), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(1)), commBlock);
            bcastBlock.add(commBlock);

            if(fpgaKernelNum != numKernels - 1) {
              commBlock = Bcons.emptyBlock();
              commBlock.add(receiver);

              for(int i = 0; i < dim - 1; i++) {
                commBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(bvar.getTotalLength())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), commBlock);
              }
              commBlock = Bcons.FOR(Xcons.Set(bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(partOffset + length)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(bvar.getTotalLength())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(dim - 1).Ref(), Xcons.IntConstant(1)), commBlock);
              bcastBlock.add(commBlock);
            }
          }

          body.add(Bcons.COMPOUND(gatherBlock));
          body.add(Bcons.COMPOUND(bcastBlock));
        } else {
          System.err.println("makeCoreBlock() [variable or not on bram, allgather skipped]");
        }

        return Bcons.COMPOUND(body);
      } else if(pragma == ACCpragma.ALLREDUCE) {
        // System.out.println("makeCoreBlock() [found Allreduce]");
        BlockList body = Bcons.emptyBody();

        Block parent = pb.getParentBlock();
        while(parent != null && (parent.Opcode() != Xcode.ACC_PRAGMA || ACCpragma.valueOf(((PragmaBlock)parent).getPragma()) != ACCpragma.BRAM)) {
          parent = parent.getParentBlock();
        }

        if(parent == null) {
          ACC.fatal("makeCoreBlock() [failed at allreduce (no bram block)");
        }

        AccBram b_directive = (AccBram) parent.getProp(AccBram.prop);

        if(b_directive == null) {
          ACC.fatal("makeCoreBlock() [failed at allreduce (no bram directive)");
        }

        AccInformation binfo = b_directive.getInfo();
        AccAllreduce directive = (AccAllreduce) pb.getProp(AccAllreduce.prop);

        if(directive == null) {
          ACC.fatal("makeCoreBlock() [failed at allreduce (no allreduce directive)");
        }

        AccInformation info = directive.getInfo();
        List<ACCvar> varlist = info.getACCvarList();

        if(varlist.size() != 1) {
          ACC.fatal("makeCoreBlock() [failed at allreduce (invalid list size)");
        }

        ACCvar var = varlist.get(0);
        ACCvar bvar = binfo.findACCvar(var.getName());

        if(bvar != null) {
          // System.out.println("makeCoreBlock() [array " + bvar.getName() + " allreduce]");

          if(findBramPointerRef(bvar.getId().Ref()) == null) {
            System.err.println("makeCoreBlock() [not on bram, allreduce skipped]");
            return Bcons.COMPOUND(body);
          }

          if(!bvar.isAlign() && !bvar.isPlace()) {
            System.err.println("makeCoreBlock() [not place, allreduce skipped]");
            return Bcons.COMPOUND(body);
          }

          Block commBlock = Bcons.emptyBlock();
          int dim = var.getDim();

          if(fpgaKernelNum == 0) {
            BlockList receivers = Bcons.emptyBody();

            for(int i = 1; i < numKernels; i++) {
              commBlock = Bcons.emptyBlock();

              Xobject receiver = makePartReduction(var.getReductionOperator(), var.getId().Type(), makeArrayRef(var.getId(), Xcons.IntConstant(0), bramIterList.subList(0, dim)), Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_RECV_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), i, 0).Ref())));
              // System.out.println("makeCoreBlock() [receiver = " + receiver + "]");
              commBlock.add(receiver);

              for(int j = 0; j < dim; j++) {
                commBlock = Bcons.FOR(Xcons.Set(bramIterList.get(j).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(j).Ref(), Xcons.IntConstant(bvar.getTotalLength())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(j).Ref(), Xcons.IntConstant(1)), commBlock);
              }
              receivers.add(commBlock);
            }
            body.add(Bcons.COMPOUND(receivers));

            commBlock = Bcons.emptyBlock();
            for(int i = 1; i < numKernels; i++) {
              Xobject sender = Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_SEND_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), 0, i).Ref(), makeArrayRef(var.getId(), Xcons.IntConstant(0), bramIterList.subList(0, dim))));
              // System.out.println("makeCoreBlock() [sender = " + sender + "]");
              commBlock.add(sender);
            }

            for(int i = 0; i < dim; i++) {
              commBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(bvar.getTotalLength())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), commBlock);
            }
            body.add(commBlock);
          } else {
            Xobject sender = Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_SEND_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), fpgaKernelNum, 0).Ref(), makeArrayRef(var.getId(), Xcons.IntConstant(0), bramIterList.subList(0, dim))));
            // System.out.println("makeCoreBlock() [sender = " + sender + "]");
            commBlock.add(sender);

            for(int i = 0; i < dim; i++) {
              commBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(bvar.getTotalLength())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), commBlock);
            }
            body.add(commBlock);

            commBlock = Bcons.emptyBlock();
            Xobject receiver = Xcons.List(Xcons.Set(makeArrayRef(var.getId(), Xcons.IntConstant(0), bramIterList.subList(0, dim)), Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_RECV_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), 0, fpgaKernelNum).Ref()))));
            // System.out.println("makeCoreBlock() [receiver = " + receiver + "]");

            commBlock.add(receiver);

            for(int i = 0; i < dim; i++) {
              commBlock = Bcons.FOR(Xcons.Set(bramIterList.get(i).Ref(), Xcons.IntConstant(0)), Xcons.binaryOp(Xcode.LOG_LT_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(bvar.getTotalLength())), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, bramIterList.get(i).Ref(), Xcons.IntConstant(1)), commBlock);
            }
            body.add(commBlock);
          }

          body.add(commBlock);
        } else {
          if(var.isArray()) {
            System.err.println("makeCoreBlock() [not on bram, allreduce skipped]");
            return Bcons.COMPOUND(body);
          }

          Block commBlock = Bcons.emptyBlock();
          // System.out.println("makeCoreBlock() [variable allreduce]");
          if(fpgaKernelNum == 0) {
            for(int i = 1; i < numKernels; i++) {
              commBlock.add(makePartReduction(var.getReductionOperator(), var.getId().Type(), var.getId().Ref(), Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_RECV_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), i, 0).Ref()))));
            }
            for(int i = 1; i < numKernels; i++) {
              commBlock.add(Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_SEND_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), 0, i).Ref(), var.getId().Ref())));
            }
          } else {
            commBlock.add(Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_SEND_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), fpgaKernelNum, 0).Ref(), var.getId().Ref())));
            commBlock.add(Xcons.Set(var.getId().Ref(), Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_RECV_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), 0, fpgaKernelNum).Ref()))));
          }

          body.add(commBlock);
        }

        return Bcons.COMPOUND(body);
      } else {
        return makeCoreBlock(b.getBody(), deviceKernelBuildInfo);
      }
    }

    case OMP_PRAGMA:
      return makeCoreBlock(b.getBody(), deviceKernelBuildInfo);

    case IF_STATEMENT: {
      if (!outerParallelisms.contains(ACCpragma.VECTOR)) {
        BlockList resultBody = Bcons.emptyBody();

        /*
        Ident sharedIfCond = resultBody.declLocalIdent("_ACC_if_cond", Xtype.charType);
        sharedIfCond.setProp(ACCgpuDecompiler.GPU_STORAGE_SHARED, true);

        Block evalCondBlock = Bcons.IF(
                                       Xcons.binaryOp(Xcode.LOG_EQ_EXPR, _accThreadIndex, Xcons.IntConstant(0)),
                                       Bcons.Statement(Xcons.Set(sharedIfCond.Ref(), b.getCondBBlock().toXobject())),
                                       null);
        Block mainIfBlock = Bcons.IF(
                                     sharedIfCond.Ref(),
                                     makeCoreBlock(b.getThenBody(), deviceKernelBuildInfo),
                                     makeCoreBlock(b.getElseBody(), deviceKernelBuildInfo));
        */

        Block mainIfBlock = Bcons.IF(b.getCondBBlock().toXobject(), makeCoreBlock(b.getThenBody(), deviceKernelBuildInfo), makeCoreBlock(b.getElseBody(), deviceKernelBuildInfo));
        // resultBody.add(evalCondBlock);
        // resultBody.add(_accSyncThreads);
        resultBody.add(mainIfBlock);

        return Bcons.COMPOUND(resultBody);
      } else {
        return b.copy();
      }
    }
    default: {
      Block resultBlock = b.copy();

      for(ACCvar var : onBramList) {
        if(var.isDivide() || var.isShadow()) {
          checkExceedArray(resultBlock, var);
        }
      }

      // Block masterBlock = makeMasterBlock(EnumSet.copyOf(outerParallelisms), resultBlock);
      // Block syncBlock = makeSyncBlock(EnumSet.copyOf(outerParallelisms));
      // return Bcons.COMPOUND(Bcons.blockList(masterBlock, syncBlock));
      return resultBlock;
    }
    }
  }

  Block makeCoreBlock(BlockList body, DeviceKernelBuildInfo deviceKernelBuildInfo) {
    if (body == null) return Bcons.emptyBlock();

    Xobject ids = body.getIdentList();
    Xobject decls = body.getDecls();
    ArrayList<XobjList> decls_bu = new ArrayList<XobjList>();

    BlockList varInitSection = Bcons.emptyBody();
    Map<Ident, Ident> rewriteIdents = new HashMap<>();
    Set<ACCpragma> outerParallelisms = AccLoop.getOuterParallelism(body.getParent());
    if (!outerParallelisms.contains(ACCpragma.VECTOR)) {
      if (ids != null) {
        for (XobjArgs args = ids.getArgs(); args != null; args = args.nextArgs()) {
          Ident id = (Ident) args.getArg();
          // System.out.println("makeCoreBlock() [id = " + id.getName() + "]");
          // id.setProp(ACCgpuDecompiler.GPU_STORAGE_SHARED, true);
        }
      }

      //move decl initializer to body
      Block childBlock = body.getHead();
      if (decls != null && !(childBlock.Opcode() == Xcode.FOR_STATEMENT && ((CforBlock)childBlock).getInitBBlock().isEmpty())) {
        List<Block> varInitBlocks = new ArrayList<Block>();
        for (Xobject x : (XobjList) decls) {
          XobjList decl = (XobjList) x;
          // System.out.println("[x = " + x + "]");
          if (decl.right() != null) {
            String varName = decl.left().getString();
            // System.out.println("makeCoreBlock() [varName = " + varName + "]");
            Ident id = ACCutil.getIdent((XobjList) ids, varName);
            Xobject initializer = decl.right();
            decls_bu.add((XobjList)Xcons.Set(id.Ref(), initializer.copy()));

            decl.setRight(null);
            {
              varInitBlocks.add(Bcons.Statement(Xcons.Set(id.Ref(), initializer)));
            }
          }
        }
        if (!varInitBlocks.isEmpty()) {
          BlockList thenBody = Bcons.emptyBody();
          for (Block b : varInitBlocks) {
            thenBody.add(b);
          }

          varInitSection.add(makeMasterBlock(EnumSet.copyOf(outerParallelisms), Bcons.COMPOUND(thenBody)));
          varInitSection.add(makeSyncBlock(EnumSet.copyOf(outerParallelisms)));
        }
      }
    }
    BlockList resultBody = Bcons.emptyBody(ids, decls);
    if(fpgaKernelNum == 0 && decls != null) {
      // System.out.println("makeCoreBlock() [decls = " + decls + "]");
      Block childBlock = body.getHead();
      if (childBlock != null && childBlock.Opcode() == Xcode.FOR_STATEMENT && !((CforBlock)childBlock).getInitBBlock().isEmpty()) {
        for (Xobject x : (XobjList) decls) {
          XobjList decl = (XobjList) x;
          // System.out.println("[x = " + x + "]");
          if (decl.right() != null) {
            String varName = decl.left().getString();
            // System.out.println("makeCoreBlock() [varName = " + varName + "]");
            Ident id = ACCutil.getIdent((XobjList) ids, varName);
            Xobject initializer = decl.right();
            // decls_bu.add((XobjList)Xcons.Set(id.Ref(), initializer.copy()));
            for(int i = 0; i < onBramIds.size(); i++) {
              replaceArrayPointer(globalIds.get(i), onBramIds.get(i), initializer);
            }
          }
        }
      }
    }
    for (Block b = body.getHead(); b != null; b = b.getNext()) {
      resultBody.add(makeCoreBlock(b, deviceKernelBuildInfo));
    }

    Block resultBlock = Bcons.COMPOUND(resultBody);

    if (ids != null) {
      for (XobjArgs args = ids.getArgs(); args != null; args = args.nextArgs()) {
        Ident id = (Ident) args.getArg();
        Ident newId = rewriteIdents.get(id);
        if(newId != null) args.setArg(newId);
      }
    }

    for(Map.Entry<Ident, Ident> entry : rewriteIdents.entrySet()){
      replaceVar(resultBlock, entry.getKey(), entry.getValue());
    }

    resultBody.insert(Bcons.COMPOUND(varInitSection));

    if(!decls_bu.isEmpty()) {
      Block childBlock = body.getHead();
      for (Xobject x : (XobjList) decls) {
        XobjList decl = (XobjList) x;
        for(XobjList decl_bu : decls_bu) {
          if(decl.left().getString().equals(decl_bu.left().getString())) {
            decl.setRight(decl_bu.right());
          }
        }
      }
    }

    return resultBlock;
  }

  Block makeCoreBlock(List<Block> blockList, DeviceKernelBuildInfo deviceKernelBuildInfo) {
    BlockList resultBody = Bcons.emptyBody();
    for (Block b : blockList) {
      resultBody.add(makeCoreBlock(b, deviceKernelBuildInfo));
    }
    return makeBlock(resultBody);
  }

  Block makeBlock(BlockList blockList) {
    if (blockList == null || blockList.isEmpty()) {
      return Bcons.emptyBlock();
    }
    if (blockList.isSingle()) {
      Xobject decls = blockList.getDecls();
      XobjList ids = blockList.getIdentList();
      if ((decls == null || decls.isEmpty()) && (ids == null || ids.isEmpty())) {
        return blockList.getHead();
      }
    }
    return Bcons.COMPOUND(blockList);
  }

  Block makeCoreBlockForStatement(CforBlock forBlock, DeviceKernelBuildInfo deviceKernelBuildInfo) {
    BlockListBuilder resultBlockBuilder = new BlockListBuilder();

    //ACCinfo info = ACCutil.getACCinfo(forBlock);
    AccInformation info = null; //= (AccInformation)forBlock.getProp(AccInformation.prop);
    Block parentBlock = forBlock.getParentBlock();
    AccDirective directive = (AccDirective) parentBlock.getProp(AccDirective.prop);
    if (directive != null)  info = directive.getInfo();

    if (info == null || !info.getPragma().isLoop()) {
      if(info != null) {
        // System.out.println("makeCoreBlockForStatement() [pragma = " + info.getPragma().toString() + "]");
      }
      // return makeSequentialLoop(forBlock, deviceKernelBuildInfo, null);
      return makeSequentialLoop(forBlock, deviceKernelBuildInfo, info);
    }

    Xobject numGangsExpr = info.getIntExpr(ACCpragma.NUM_GANGS); //info.getNumGangsExp();
    if (numGangsExpr == null) numGangsExpr = info.getIntExpr(ACCpragma.GANG);
    Xobject numWorkersExpr = info.getIntExpr(ACCpragma.NUM_WORKERS);
    if (numWorkersExpr == null) numWorkersExpr = info.getIntExpr(ACCpragma.WORKER);
    Xobject vectorLengthExpr = info.getIntExpr(ACCpragma.VECT_LEN); //info.getVectorLengthExp();
    if (vectorLengthExpr == null) vectorLengthExpr = info.getIntExpr(ACCpragma.VECTOR);
    //    System.out.println(numGangsExpr);
    if (numGangsExpr != null) gpuManager.setNumGangs(numGangsExpr);
    if (numWorkersExpr != null) gpuManager.setNumWorkers(numWorkersExpr);
    if (vectorLengthExpr != null) gpuManager.setVectorLength(vectorLengthExpr);

    String execMethodName = gpuManager.getMethodName(forBlock);
    EnumSet<ACCpragma> execMethodSet = gpuManager.getMethodType(forBlock);
    if (execMethodSet.isEmpty() || execMethodSet.contains(ACCpragma.SEQ)) { //if execMethod is not defined or seq
      return makeSequentialLoop(forBlock, deviceKernelBuildInfo, info);
      //      loopStack.push(new Loop(forBlock));
      //      BlockList body = Bcons.blockList(makeCoreBlock(forBlock.getBody(), deviceKernelBuildInfo, prevExecMethodName));
      //      loopStack.pop();
      //      return Bcons.FOR(forBlock.getInitBBlock(), forBlock.getCondBBlock(), forBlock.getIterBBlock(), body);
    }

    if (!execMethodSet.isEmpty()) {
      for (ACCpragma execMethod : execMethodSet) {
        // System.out.println("makeCoreBlockForStatement() [" + execMethod + "]");
      }
    }

    if(true) {
      return makeSequentialLoop(forBlock, deviceKernelBuildInfo, info);
    }

    List<Block> cacheLoadBlocks = new ArrayList<Block>();

    LinkedList<CforBlock> collapsedForBlockList = new LinkedList<CforBlock>();

    Set<String> indVarSet = new LinkedHashSet<String>();
    {
      CforBlock tmpForBlock = forBlock;
      collapsedForBlockList.add(forBlock);
      indVarSet.add(forBlock.getInductionVar().getSym());

      Xobject collapseNumExpr = info.getIntExpr(ACCpragma.COLLAPSE);
      int collapseNum = collapseNumExpr != null ? collapseNumExpr.getInt() : 1;
      for (int i = 1; i < collapseNum; i++) {
        tmpForBlock = AccLoop.findOutermostTightlyNestedForBlock(tmpForBlock.getBody().getHead());
        collapsedForBlockList.add(tmpForBlock);
        indVarSet.add(tmpForBlock.getInductionVar().getSym());
      }
    }

    //private
    {
      for (ACCvar var : info.getACCvarList()) {
        if (!var.isPrivate()) {
          continue;
        }
        if (indVarSet.contains(var.getSymbol())) {
          continue;
        }
        Xtype varType = var.getId().Type();
        if (execMethodSet.contains(ACCpragma.VECTOR)) {
          resultBlockBuilder.declLocalIdent(var.getName(), varType);
        } else if (execMethodSet.contains(ACCpragma.GANG)) {
          if (varType.isArray()) {
            Ident arrayPtrId = Ident.Local(var.getName(), Xtype.Pointer(varType.getRef()));
            Ident privateArrayParamId = Ident.Param("_ACC_prv_" + var.getName(), Xtype.voidPtrType);
            deviceKernelBuildInfo.addLocalId(arrayPtrId);
            deviceKernelBuildInfo.addParamId(privateArrayParamId);

            try {
              Xobject sizeObj = Xcons.binaryOp(Xcode.MUL_EXPR,
                                               ACCutil.getArrayElmtCountObj(varType),
                                               Xcons.SizeOf(varType.getArrayElementType()));
              XobjList initPrivateFuncArgs = Xcons.List(Xcons.Cast(Xtype.Pointer(Xtype.voidPtrType), arrayPtrId.getAddr()), privateArrayParamId.Ref(), sizeObj);
              Block initPrivateFuncCall = ACCutil.createFuncCallBlock("_ACC_init_private", initPrivateFuncArgs);
              deviceKernelBuildInfo.addInitBlock(initPrivateFuncCall);
              allocList.add(Xcons.List(var.getId(), Xcons.IntConstant(0), sizeObj));
            } catch (Exception e) {
              ACC.fatal(e.getMessage());
            }
          } else {
            Ident privateLocalId = Ident.Local(var.getName(), varType);
            privateLocalId.setProp(ACCgpuDecompiler.GPU_STORAGE_SHARED, true);
            resultBlockBuilder.addIdent(privateLocalId);
          }
        }
      }
    }

    // for pezy-sc, safe sync
    boolean hasReduction = false;
    for (ACCvar var : info.getACCvarList()) {
      if (var.isReduction()){
        hasReduction = true;
	break;
      }
    }
    if (hasReduction){
      if (hasGangSync && execMethodSet.contains(ACCpragma.GANG)){
        resultBlockBuilder.addFinalizeBlock(Bcons.Statement(_accSyncGangs));
      }
    }

    //begin reduction
    List<Reduction> reductionList = new ArrayList<Reduction>();
    //Iterator<ACCvar> vars = info.getVars();
    //while (vars.hasNext()) {
    //ACCvar var = vars.next();
    for (ACCvar var : info.getACCvarList()) {

      // System.out.println("makeCoreBlockForStatement() [ACCvar var = " + var.getName() + "]");

      if (!var.isReduction()) continue;

      Reduction reduction = reductionManager.addReduction(var, execMethodSet);

      if(_readOnlyOuterIdSet.contains(var.getId())){
        ACC.fatal("reduction variable is read-only, isn't it?");
      }
      if (! reduction.onlyKernelLast()) {
        resultBlockBuilder.addIdent(reduction.getLocalReductionVarId());
        resultBlockBuilder.addInitBlock(reduction.makeInitReductionVarFuncCall());
        resultBlockBuilder.addFinalizeBlock(reduction.makeInKernelReductionFuncCall(null));
      }

      reductionList.add(reduction);
    }//end reduction

    //make calc idx funcs
    List<Block> calcIdxFuncCalls = new ArrayList<Block>();
    XobjList vIdxIdList = Xcons.IDList();
    XobjList nIterIdList = Xcons.IDList();
    XobjList indVarIdList = Xcons.IDList();
    Boolean has64bitIndVar = false;
    for (CforBlock tmpForBlock : collapsedForBlockList) {
      String indVarName = tmpForBlock.getInductionVar().getName();
      Xtype indVarType = tmpForBlock.findVarIdent(indVarName).Type();
      Xtype idxVarType = Xtype.unsignedType;
      switch (indVarType.getBasicType()) {
      case BasicType.INT:
      case BasicType.UNSIGNED_INT:
        idxVarType = Xtype.unsignedType;
        break;
      case BasicType.LONGLONG:
      case BasicType.UNSIGNED_LONGLONG:
        idxVarType = Xtype.unsignedlonglongType;
        has64bitIndVar = true;
        break;
      }
      Xobject init = tmpForBlock.getLowerBound().copy();
      Xobject cond = tmpForBlock.getUpperBound().copy();
      Xobject step = tmpForBlock.getStep().copy();
      Ident vIdxId = Ident.Local("_ACC_idx_" + indVarName, idxVarType);
      Ident indVarId = Ident.Local(indVarName, indVarType);
      Ident nIterId = resultBlockBuilder.declLocalIdent("_ACC_niter_" + indVarName, idxVarType);
      Block calcNiterFuncCall = ACCutil.createFuncCallBlock("_ACC_calc_niter", Xcons.List(nIterId.getAddr(), init, cond, step));
      Block calcIdxFuncCall = ACCutil.createFuncCallBlock(ACC_CALC_IDX_FUNC, Xcons.List(vIdxId.Ref(), indVarId.getAddr(), init, cond, step));

      resultBlockBuilder.addInitBlock(calcNiterFuncCall);

      vIdxIdList.add(vIdxId);
      nIterIdList.add(nIterId);
      indVarIdList.add(indVarId);
      calcIdxFuncCalls.add(calcIdxFuncCall);
    }

    Xtype globalIdxType = has64bitIndVar ? Xtype.unsignedlonglongType : Xtype.unsignedType;

    Ident iterIdx = resultBlockBuilder.declLocalIdent("_ACC_" + execMethodName + "_idx", globalIdxType);
    Ident iterInit = resultBlockBuilder.declLocalIdent("_ACC_" + execMethodName + "_init", globalIdxType);
    Ident iterCond = resultBlockBuilder.declLocalIdent("_ACC_" + execMethodName + "_cond", globalIdxType);
    Ident iterStep = resultBlockBuilder.declLocalIdent("_ACC_" + execMethodName + "_step", globalIdxType);

    XobjList initIterFuncArgs = Xcons.List(iterInit.getAddr(), iterCond.getAddr(), iterStep.getAddr());
    Xobject nIterAll = Xcons.IntConstant(1);
    for (Xobject x : nIterIdList) {
      Ident nIterId = (Ident) x;
      nIterAll = Xcons.binaryOp(Xcode.MUL_EXPR, nIterAll, nIterId.Ref());
    }
    initIterFuncArgs.add(nIterAll);

    Block initIterFunc = ACCutil.createFuncCallBlock(ACC_INIT_ITER_FUNC_PREFIX + execMethodName, initIterFuncArgs);
    resultBlockBuilder.addInitBlock(initIterFunc);


    //make clac each idx from virtual idx
    Block calcEachVidxBlock = makeCalcIdxFuncCall(vIdxIdList, nIterIdList, iterIdx);

    //push Loop to stack
    Loop thisLoop = new Loop(forBlock, iterIdx, iterInit, iterCond, iterStep);
    loopStack.push(thisLoop);

    List<Cache> cacheList = new ArrayList<Cache>();

    if (false) {
      transLoopCache(forBlock, resultBlockBuilder, cacheLoadBlocks, cacheList);
    }

    BlockList parallelLoopBody = Bcons.emptyBody();
    parallelLoopBody.add(calcEachVidxBlock);

    for (Block b : calcIdxFuncCalls) parallelLoopBody.add(b);

    // add cache load funcs
    for (Block b : cacheLoadBlocks) {
      parallelLoopBody.add(b);
    }
    // add inner block
    BlockList innerBody = collapsedForBlockList.getLast().getBody();
    Block coreBlock = makeCoreBlock(innerBody, deviceKernelBuildInfo);

    //rewirteCacheVars
    for (Cache cache : cacheList) {
      cache.rewrite(coreBlock);
    }
    parallelLoopBody.add(coreBlock);

    //add the cache barrier func
    if (!cacheLoadBlocks.isEmpty()) {
      parallelLoopBody.add(_accSyncThreads);
    }

    {
      XobjList forBlockListIdents = (XobjList) indVarIdList.copy();//Xcons.List(indVarId);
      forBlockListIdents.mergeList(vIdxIdList);
      ///insert
      parallelLoopBody.setIdentList(forBlockListIdents);
    }

    Block parallelLoopBlock = Bcons.FOR(
                                        Xcons.Set(iterIdx.Ref(), iterInit.Ref()),
                                        Xcons.binaryOp(Xcode.LOG_LT_EXPR, iterIdx.Ref(), iterCond.Ref()),
                                        Xcons.asgOp(Xcode.ASG_PLUS_EXPR, iterIdx.Ref(), iterStep.Ref()),
                                        Bcons.COMPOUND(parallelLoopBody));

    //rewriteReductionvar
    for (Reduction red : reductionList) {
      red.rewrite(parallelLoopBlock);
    }

    //make resultBody
    resultBlockBuilder.add(parallelLoopBlock);

    if (hasGangSync && execMethodSet.contains(ACCpragma.GANG)){
      resultBlockBuilder.addFinalizeBlock(Bcons.Statement(_accSyncGangs));
    } else if (execMethodSet.contains(ACCpragma.VECTOR)) {
      // resultBlockBuilder.addFinalizeBlock(Bcons.Statement(_accSyncThreads));
      // System.out.println("makeCoreBlockForStatement() [_accSyncThreads = " + _accSyncThreads.toString() + " ]");

      Iterator<Reduction> reductionIterator = reductionManager.BlockReductionIterator();
      while(reductionIterator.hasNext()) {
        Reduction reduction = reductionIterator.next();
        Xtype constParamType = reduction.varId.Type();
        // System.out.println("makeCoreBlockForStatement() [reduction.varId.Type() = " + constParamType.toString() + "]");
        Ident constParamId = Ident.Param(reduction.var.toString(), constParamType);
        Ident localId = reduction.localVarId;

        Xobject finalize = Xcons.Set(constParamId.Ref(), localId.Ref());
        resultBlockBuilder.addFinalizeBlock(Bcons.Statement(finalize));
      }
    }

    //pop stack
    loopStack.pop();

    BlockList resultBody = resultBlockBuilder.build();
    return Bcons.COMPOUND(resultBody);
  }

  void transLoopCache(CforBlock forBlock, BlockListBuilder resultBlockBuilder, List<Block> cacheLoadBlocks, List<Cache> cacheList) {
    Block headBlock = forBlock.getBody().getHead();
    if (headBlock == null)  return;
    AccDirective directive = (AccDirective)headBlock.getProp(AccDirective.prop);
    if(directive != null){
      AccInformation headInfo = directive.getInfo();
      if (headInfo.getPragma() == ACCpragma.CACHE) {
        for (ACCvar var : headInfo.getACCvarList()) {
          if (!var.isCache()) continue;


          Ident cachedId = var.getId();
          XobjList subscripts = var.getSubscripts();

          Cache cache = sharedMemory.alloc(cachedId, subscripts);

          resultBlockBuilder.addInitBlock(cache.initFunc);
          cacheLoadBlocks.add(cache.loadBlock);

          resultBlockBuilder.addIdent(cache.cacheId);
          resultBlockBuilder.addIdent(cache.cacheSizeArrayId);
          resultBlockBuilder.addIdent(cache.cacheOffsetArrayId);

          //for after rewrite
          cacheList.add(cache);
        }//end while
      }
    }
  }

  Block makeSequentialLoop(CforBlock forBlock, DeviceKernelBuildInfo deviceKernelBuildInfo, AccInformation info) {
    loopStack.push(new Loop(forBlock));
    // System.out.println("makeSequencialLoop() [call Inner Block]");
    BlockList body = Bcons.blockList(makeCoreBlock(forBlock.getBody(), deviceKernelBuildInfo));
    // System.out.println("makeSequencialLoop() [return from Inner Block]");

    loopStack.pop();

    forBlock.Canonicalize();
    Ident originalInductionVarId = null;
    Xobject originalInductionVar = null;
    if(forBlock.isCanonical()) {
      originalInductionVar = forBlock.getInductionVar();
      originalInductionVarId = forBlock.findVarIdent(originalInductionVar.getName());
    }else{
      ACC.fatal("non canonical loop");
    }

    //FIXME this is not good for nothing parallelism kernel
    Set<ACCpragma> outerParallelisms = AccLoop.getOuterParallelism(forBlock);
    BlockList resultBody = Bcons.emptyBody();
    if(info != null){
      for(ACCvar var : info.getACCvarList()){
        if(var.isArray()) {
          Ident varId = var.getId();
          if(varId.Type().getKind() == Xtype.ARRAY) {
            ArrayType at = (ArrayType)varId.Type();
            // System.out.println("makeSequentialLoop() [var = " + var.toString() + "]");
            // System.out.println("makeSequentialLoop() [var.getSize() = " + var.getSize().toString() + "]");
            // System.out.println("makeSequentialLoop() [var.getId().Type().getArraySize() = " + at.getArraySize() + "]");
          }

        }
        if(var.isPrivate()){
          if(var.getId() != originalInductionVarId) {
            // resultBody.declLocalIdent(var.getName(), var.getId().Type());
            // System.out.println("makeSequentialLoop() [var = " + var.toString() + "]");
          }
        }
      }
    }

  /*
    if (outerParallelisms.contains(ACCpragma.VECTOR)) {
      Block loop = Bcons.FOR(forBlock.getInitBBlock(), forBlock.getCondBBlock(), forBlock.getIterBBlock(), body);
      {
        LineNo ln = loop.getLineNo();
        if(ln == null) ln = new LineNo("",0);
        ln.setLinePrefix("// AccKernel_FPGA.java makeSequentialLoop() 2");
        System.out.println("Add LinePrefix !!!");
        loop.setLineNo(ln);
      }
      resultBody.add(loop);System.out.println("return here!!!");
      return Bcons.COMPOUND(resultBody);
    }
  */

    XobjList identList = resultBody.getIdentList();
    if(identList != null){
      for(Xobject xobj : identList){
        Ident id = (Ident)xobj;
        id.setProp(ACCgpuDecompiler.GPU_STORAGE_SHARED, true);
      }
    }

    Ident inductionVarId = resultBody.declLocalIdent("_ACC_loop_iter_" + originalInductionVar.getName(), originalInductionVar.Type());

    // BlockList newbody = new BlockList(makeCoreBlock(body, deviceKernelBuildInfo));
    // Block mainLoop = Bcons.FOR(Xcons.Set(inductionVarId.Ref(), forBlock.getLowerBound()), Xcons.binaryOp(Xcode.LOG_LT_EXPR, inductionVarId.Ref(), forBlock.getUpperBound()), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, inductionVarId.Ref(), forBlock.getStep()), Bcons.COMPOUND(newbody));

    // Block mainLoop = Bcons.FOR(Xcons.Set(inductionVarId.Ref(), forBlock.getLowerBound()), Xcons.binaryOp(Xcode.LOG_LT_EXPR, inductionVarId.Ref(), forBlock.getUpperBound()), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, inductionVarId.Ref(), forBlock.getStep()), Bcons.COMPOUND(body));
    Block mainLoop = Bcons.FOR(Xcons.Set(inductionVarId.Ref(), forBlock.getLowerBound()), Xcons.binaryOp(forBlock.getCheckOpcode(), inductionVarId.Ref(), forBlock.getUpperBound()), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, inductionVarId.Ref(), forBlock.getStep()), Bcons.COMPOUND(body));
    // System.out.println("makeSequencialLoop() [forBlock.getUpperBound() = "+ forBlock.getUpperBound().toString() + "]");
    // System.out.println("makeSequencialLoop() [forBlock.getCheckOpcode() = "+ forBlock.getCheckOpcode().toXcodeString() + "]");
    // System.out.println("makeSequencialLoop() [forBlock.getLowerBound() = "+ forBlock.getLowerBound().toString() + "]");

    boolean hasReduction = false;
    boolean hasRemain = false;
    boolean mulkerReduction = false;
    boolean innerLoop = hasInnerLoop(body);

    List<ACCvar> redList = new ArrayList<ACCvar>();
    BlockList partReduction = Bcons.emptyBody();
    BlockList partRemain = Bcons.emptyBody();
    if(info != null) {
      for (ACCvar var : info.getACCvarList()) {
        if (var.isReduction()) {
          // System.out.println("makeSequencialLoop() [reduction var = " + var.getName() + "]");
          redList.add(var);
          hasReduction = true;
        }
      }
    }

    {
      AccInformation ainfo = null; // = (AccInformation)forBlock.getProp(AccInformation.prop);
      Block parentBlock = forBlock.getParentBlock();
      AccDirective directive = (AccDirective) parentBlock.getProp(AccDirective.prop);
      if (directive != null) {
        ainfo = directive.getInfo();
      }
      if(ainfo != null) {
        LineNo ln = mainLoop.getLineNo();
        if(ln == null) {
          ln = new LineNo("",0);
        }

        ACCpragma pragma = ainfo.getPragma();

        // set #pragma ivdep
        if(pragma == ACCpragma.LOOP) {
          ln.setLinePrefix("#pragma ivdep");
          // System.out.println("Add LinePrefix [ivdep]");
          mainLoop.setLineNo(ln);
        }


        int lowerBound = calcXobject(forBlock.getLowerBound());
        int upperBound = calcXobject(forBlock.getUpperBound());
        int numStep = forBlock.getStep().getInt();
        AccInformation.Clause mulkerClause = ainfo.findClause(ACCpragma.MULKER_LENGTH);

        if(mulkerClause != null && numKernels > 1) {
          // System.out.println("makeSequencialLoop() [lowerBound = " + lowerBound + ", upperBound = " + upperBound + ", numStep = " + numStep + "]");
          Xobject baseLengthXobject = mulkerClause.getIntExpr();

          if(isCalculatable(baseLengthXobject) && isCalculatable(forBlock.getLowerBound()) && isCalculatable(forBlock.getUpperBound())) {
            int baseLength;
            int partOffset;
            int partLength;
            int localLength;
            int newInit;
            int newCond;
            Xcode xCond;

            baseLength = calcXobject(baseLengthXobject);
            partLength = (baseLength - 1) / numKernels + 1;
            partOffset = partLength * fpgaKernelNum;
            localLength = partLength;
            if(fpgaKernelNum == numKernels - 1) {
              localLength = baseLength - partOffset;
            }
            newInit = 0;
            newCond = 0;
            xCond = forBlock.getCheckOpcode();

            // System.out.println("makeSequencialLoop() [baseLength = " + baseLength + ", partOffset = " + partOffset + ", localLength = " + localLength + "]");

            if(xCond == Xcode.LOG_LT_EXPR) {
              if(upperBound < partOffset || lowerBound > partOffset + localLength) {
                System.err.println("makeSequencialLoop() [exceed bound, block deleted]");

                return Bcons.emptyBlock();
              }

              newInit = lowerBound;
              while(newInit < partOffset) {
                newInit += numStep;
              }
              newCond = partOffset + localLength;
              while(newCond > upperBound) {
                newCond--;
              }
            } else if(xCond == Xcode.LOG_GT_EXPR) {
              if(lowerBound < partOffset || upperBound >= partOffset + localLength) {
                System.err.println("makeSequencialLoop() [exceed bound, block deleted]");

                return Bcons.emptyBlock();
              }

              newInit = partOffset + localLength - 1;
              while(newInit > lowerBound) {
                newInit += numStep;
              }
              newCond = partOffset - 1;
            }

            mainLoop = Bcons.FOR(Xcons.Set(inductionVarId.Ref(), Xcons.IntConstant(newInit)), Xcons.binaryOp(forBlock.getCheckOpcode(), inductionVarId.Ref(), Xcons.IntConstant(newCond)), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, inductionVarId.Ref(), forBlock.getStep()), Bcons.COMPOUND(body));

            lowerBound = newInit;
            upperBound = newCond;

            setLoopArrayFrontOffset(mainLoop, originalInductionVarId, partOffset);
          } else {
            System.err.println("makeSequencialLoop() [mulker skipped]");
          }
        }

        int numIter = upperBound - lowerBound;
        mulkerReduction = hasReduction && mulkerClause != null && (numKernels > 1);

        AccInformation.Clause unrollClause = ainfo.findClause(ACCpragma.UNROLL);
        if(unrollClause != null) {
          // System.out.println("makeSequencialLoop() [unroll]");
          Xobject unrollNumExpr = unrollClause.getIntExpr();
          int numUnroll = 0;
          String lprefix = ln.getLinePrefix();

          if(unrollNumExpr != null) {
            numUnroll = unrollNumExpr.getInt();
            if(numUnroll == 0 && numIter > UNROLL_MAX) {
              numUnroll = UNROLL_MAX;
            }
          }
          if (lprefix == null) {
            lprefix = "";
          } else {
            lprefix = lprefix + "\n";
          }

          // System.out.println("makeSequencialLoop() [numIter = " + numIter + "]");
          // System.out.println("makeSequencialLoop() [numStep = " + numStep + "]");
          // System.out.println("makeSequencialLoop() [numUnroll = " + numUnroll + "]");

          if(numUnroll == 0) {
            ln.setLinePrefix(lprefix + "#pragma unroll");
            // System.out.println("Add LinePrefix [unroll]");
            mainLoop.setLineNo(ln);
          } else if(numUnroll == 1) {
            System.err.println("Loop Unrolling: Skipped (numUnroll = 1)");
            hasReduction = false;
          } else {
            if(!hasReduction && !innerLoop && numUnroll > 1) {
              ln.setLinePrefix(lprefix + "#pragma unroll " + numUnroll);
              // System.out.println("Add LinePrefix [unroll]");
              mainLoop.setLineNo(ln);
            } else if(!isCalculatable(forBlock.getLowerBound()) || !isCalculatable(forBlock.getUpperBound()) || !isCalculatable(forBlock.getStep())) {
              hasReduction = false;
              System.err.println("Loop Unrolling: Skipped (not constant)");
            } else {
              BlockList unrollInit = Bcons.emptyBody();
              BlockList unrolledBody = Bcons.emptyBody();
              // System.out.println("numIter = " + numIter + ", numUnroll = " + numUnroll + ", numStep = " + numStep);

              if (numIter % (numUnroll * numStep) == 0) {
                String partPrefix = "__ACC_UNROLL_";
                for(int i = 0; i < numUnroll; i++) {
                  BlockList list = body.copy();
                  Block bcopy = Bcons.COMPOUND(list);

                  for(ACCvar redbase : redList) {
                    Ident part = resultBody.declLocalIdent(partPrefix + redbase.getName() + i, redbase.getId().Type());

                    unrollInit.add(Xcons.Set(part.Ref(), unrollInitializer(redbase.getReductionOperator(), redbase.getId().Type())));

                    replaceVar(bcopy, redbase.getId(), part);

                    partReduction.add(makePartReduction(redbase.getReductionOperator(), redbase.getId(), part));
                  }
                  setOffset(bcopy, originalInductionVarId, i * numStep);
                  for(Block b = list.getHead(); b != null; b = b.getNext()) {
                    HashSet<String> replaced = new HashSet<String>();
                    setOffsetInDecl(b, originalInductionVarId, i, replaced);
                  }
                  unrolledBody.add(bcopy);
                }
                resultBody.add(Bcons.COMPOUND(unrollInit));
                // System.out.println("makeSequentialLoop() [unrolledBody = " + unrolledBody.toString() + "]");

                mainLoop = Bcons.FOR(Xcons.Set(inductionVarId.Ref(), Xcons.IntConstant(lowerBound)), Xcons.binaryOp(forBlock.getCheckOpcode(), inductionVarId.Ref(), Xcons.binaryOp(Xcode.MINUS_EXPR, Xcons.IntConstant(upperBound), Xcons.binaryOp(Xcode.MUL_EXPR, Xcons.IntConstant(numUnroll - 1), forBlock.getStep()))), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, inductionVarId.Ref(), Xcons.binaryOp(Xcode.MUL_EXPR, forBlock.getStep(), Xcons.IntConstant(numUnroll))), Bcons.COMPOUND(unrolledBody));

                // System.out.println("Loop Unrolling: Skipped (Reduction)");
              } else {
                hasRemain = true;
                boolean plenty = false;

                if(forBlock.getCheckOpcode() == Xcode.LOG_LT_EXPR) {
                  plenty = numIter >= numUnroll * numStep;
                } else {
                  plenty = numIter <= numUnroll * numStep;
                }

                if(plenty) {
                  String partPrefix = "__ACC_UNROLL_";
                  for(int i = 0; i < numUnroll; i++) {
                    BlockList list = body.copy();
                    Block bcopy = Bcons.COMPOUND(list);

                    for(ACCvar redbase : redList) {
                      Ident part = resultBody.declLocalIdent(partPrefix + redbase.getName() + i, redbase.getId().Type());
                      unrollInit.add(Xcons.Set(part.Ref(), unrollInitializer(redbase.getReductionOperator(), redbase.getId().Type())));

                      replaceVar(bcopy, redbase.getId(), part);

                      partReduction.add(makePartReduction(redbase.getReductionOperator(), redbase.getId(), part));
                    }
                    setOffset(bcopy, originalInductionVarId, i * numStep);
                    for(Block b = list.getHead(); b != null; b = b.getNext()) {
                      HashSet<String> replaced = new HashSet<String>();
                      setOffsetInDecl(b, originalInductionVarId, i, replaced);
                    }

                    unrolledBody.add(bcopy);
                  }

                  mainLoop = Bcons.FOR(Xcons.Set(inductionVarId.Ref(), Xcons.IntConstant(lowerBound)), Xcons.binaryOp(forBlock.getCheckOpcode(), inductionVarId.Ref(), Xcons.binaryOp(Xcode.MINUS_EXPR, Xcons.IntConstant(upperBound), Xcons.binaryOp(Xcode.MUL_EXPR, Xcons.IntConstant(numUnroll - 1), forBlock.getStep()))), Xcons.asgOp(Xcode.ASG_PLUS_EXPR, inductionVarId.Ref(), Xcons.binaryOp(Xcode.MUL_EXPR, forBlock.getStep(), Xcons.IntConstant(numUnroll))), Bcons.COMPOUND(unrolledBody));
                }

                String remainPrefix = "__ACC_UNROLL_REMAIN_";
                BlockList baseList = body.copy();
                Block rcopyBase = Bcons.COMPOUND(baseList);
                ArrayList<Ident> remList = new ArrayList<Ident>();

                for(ACCvar redbase : redList) {
                  Ident remain = resultBody.declLocalIdent(remainPrefix + redbase.getName(), redbase.getId().Type());
                  remList.add(remain);
                  unrollInit.add(Xcons.Set(remain.Ref(), unrollInitializer(redbase.getReductionOperator(), redbase.getId().Type())));
                  partReduction.add(makePartReduction(redbase.getReductionOperator(), redbase.getId(), remain));

                  replaceVar(rcopyBase, redbase.getId(), remain);
                }

                int firstRemain = lowerBound;
                int upper = upperBound;

                if(forBlock.getCheckOpcode() == Xcode.LOG_LT_EXPR) {
                  while(firstRemain + numStep * numUnroll < upper) {
                      firstRemain += numStep * numUnroll;
                  }

                  for(int i = firstRemain; i < upper; i += numStep) {
                    BlockList rlist = baseList.copy();
                    Block rcopy = Bcons.COMPOUND(rlist);

                    assignConstInt(rcopy, originalInductionVarId, i);
                    for(Block b = rlist.getHead(); b != null; b = b.getNext()) {
                      assignConstIntInDecl(b, originalInductionVarId, i);
                    }

                    if(innerLoop) {
                      for(ACCvar redbase : redList) {
                        Ident innerRemain = resultBody.declLocalIdent(remainPrefix + redbase.getName() + "_" + i, redbase.getId().Type());
                        Ident remain = null;
                        for(Ident ri : remList) {
                          if(ri.getName().equals(remainPrefix + redbase.getName())) {
                            remain = ri;
                            break;
                          }
                        }
                        if(remain == null) {
                          continue;
                        }

                        unrollInit.add(Xcons.Set(innerRemain.Ref(), unrollInitializer(redbase.getReductionOperator(), redbase.getId().Type())));
                        partReduction.insert(makePartReduction(redbase.getReductionOperator(), remain, innerRemain));

                        replaceVar(rcopy, remain, innerRemain);
                      }
                    }

                    partRemain.add(rcopy);
                  }
                } else {
                  while(firstRemain + numStep * numUnroll > upper) {
                    firstRemain += numStep * numUnroll;
                  }

                  for(int i = firstRemain; i > upper; i += numStep) {
                    BlockList rlist = baseList.copy();
                    Block rcopy = Bcons.COMPOUND(rlist);


                    assignConstInt(rcopy, originalInductionVarId, i);
                    for(Block b = rlist.getHead(); b != null; b = b.getNext()) {
                      assignConstIntInDecl(b, originalInductionVarId, i);
                    }

                    if(innerLoop) {
                      for(ACCvar redbase : redList) {
                        Ident innerRemain = resultBody.declLocalIdent(remainPrefix + redbase.getName() + "_" + i, redbase.getId().Type());
                        Ident remain = null;
                        for(Ident ri : remList) {
                          if(ri.getName().equals(remainPrefix + redbase.getName())) {
                            remain = ri;
                            break;
                          }
                        }
                        if(remain == null) {
                          continue;
                        }

                        unrollInit.add(Xcons.Set(innerRemain.Ref(), unrollInitializer(redbase.getReductionOperator(), redbase.getId().Type())));
                        partReduction.insert(makePartReduction(redbase.getReductionOperator(), remain, innerRemain));

                        replaceVar(rcopy, remain, innerRemain);
                      }
                    }

                    partRemain.add(rcopy);
                  }
                }

                resultBody.add(Bcons.COMPOUND(unrollInit));

                // System.out.println("Loop Unrolling: Skipped (Remain)");
              }
            }
          }
        } else {
          // reset ivdep for mulker
          String lprefix = ln.getLinePrefix();

          if(lprefix != null) {
            mainLoop.setLineNo(ln);
          }
        }
      }

      /*
      EnumSet<ACCpragma> execMethodSet = gpuManager.getMethodType(forBlock);
      if(!execMethodSet.isEmpty()) {
        for (ACCpragma execMethod : execMethodSet) {
          System.out.println("makeSequencialLoop() [" + execMethod + "]");
        }
      }
      if(execMethodSet.contains(ACCpragma.unroll)) {
        System.out.println("makeSequencialLoop() [unroll]");
        Xobject unrollNumExpr = info.getIntExpr(ACCpragma.unroll);
        boolean hasReduction = false;
        for (ACCvar var : info.getACCvarList()) {
          if(var.isReduction()){
            hasReduction = true;
            break;
          }
        }
        if(hasReduction && unrollNumExpr != null) {
          int numIter = forBlock.getUpperBound().getInt();
          int numUnroll = unrollNumExpr.getInt();

          if(numIter % numUnroll == 0) {

          } else {
            System.out.println("Unrolling for Reduction: Skipped");
          }
        } else {
          String lprefix = ln.getLinePrefix();
          if(lprefix == null) {
            lprefix = "";
          } else {
            lprefix = lprefix + "\n";
          }
          if(unrollNumExpr != null) {
            ln.setLinePrefix(lprefix + "#pragma unroll " + unrollNumExpr.getInt());
          } else {
            ln.setLinePrefix(lprefix + "#pragma unroll");
          }
          System.out.println("Add LinePrefix !!!");
          mainLoop.setLineNo(ln);
        }
      }
      */

      // test
      /*
      if(true) {
        System.out.println("makeSequencialLoop() [unroll test]");
        // Xobject unrollNumExpr = info.getIntExpr(ACCpragma.unroll);
        if (true) {
          ln.setLinePrefix("#pragma unroll 2");
        } else {
          ln.setLinePrefix("#pragma unroll");
        }
        System.out.println("Add LinePrefix !!!");
        mainLoop.setLineNo(ln);
      }
    */
    }

    resultBody.add(mainLoop);

    ACCvar var = (info != null)? info.findACCvar(originalInductionVar.getName()) : null;
    if(var == null || !(var.isPrivate() || var.isFirstprivate())) {
      // Block endIf = Bcons.IF(Xcons.binaryOp(Xcode.LonOG_EQ_EXPR, _accThreadIndex, Xcons.IntConstant(0)), Bcons.Statement(Xcons.Set(originalInductionVar, inductionVarId.Ref())), null);
      // resultBody.add(endIf);
    }
    // resultBody.add(_accSyncThreads);

    replaceVar(mainLoop, originalInductionVarId, inductionVarId);

    // System.out.println("makeSequencialLoop() [mainloop = " + mainLoop.toString() + "]");

    if(hasRemain) {
      resultBody.add(Bcons.COMPOUND(partRemain));
    }

    if(hasReduction) {
      // System.out.println("makeSequencialLoop() [partReduction = " + partReduction.toString() + "]");
      resultBody.add(Bcons.COMPOUND(partReduction));
    }

    if(mulkerReduction) {
      BlockList commBody = Bcons.emptyBody();

      for (ACCvar redvar : info.getACCvarList()) {
        if (redvar.isReduction()) {
          if(fpgaKernelNum == 0) {
            for(int i = 1; i < numKernels; i++) {
              commBody.add(makePartReduction(redvar.getReductionOperator(), redvar.getId().Type(), redvar.getId().Ref(), Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(redvar.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_RECV_FUNC_NAME), Xcons.List(getKernelChannel(redvar.getElementType(), i, 0).Ref()))));
            }
            for(int i = 1; i < numKernels; i++) {
              commBody.add(Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(redvar.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_SEND_FUNC_NAME), Xcons.List(getKernelChannel(redvar.getElementType(), 0, i).Ref(), redvar.getId().Ref())));
            }
          } else {
            commBody.add(Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(redvar.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_SEND_FUNC_NAME), Xcons.List(getKernelChannel(redvar.getElementType(), fpgaKernelNum, 0).Ref(), redvar.getId().Ref())));
            commBody.add(Xcons.Set(redvar.getId().Ref(), Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(redvar.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_RECV_FUNC_NAME), Xcons.List(getKernelChannel(redvar.getElementType(), 0, fpgaKernelNum).Ref()))));
          }
          // System.out.println("makeSequencialLoop() [commBody = " + commBody + "]");
        }
      }

      resultBody.add(Bcons.COMPOUND(commBody));
    }

    return Bcons.COMPOUND(resultBody);
  }


  private Block makeCalcIdxFuncCall(XobjList vidxIdList, XobjList nIterIdList, Ident vIdx) {
    int i;
    Xobject result = vIdx.Ref();
    Ident funcId = ACCutil.getMacroFuncId("_ACC_calc_vidx", Xtype.intType);

    for (i = vidxIdList.Nargs() - 1; i > 0; i--) {
      Ident indVarId = (Ident) (vidxIdList.getArg(i));
      Ident nIterId = (Ident) (nIterIdList.getArg(i));
      Block callBlock = Bcons.Statement(funcId.Call(Xcons.List(indVarId.getAddr(), nIterId.Ref(), result)));
      result = callBlock.toXobject();
    }

    Ident indVarId = (Ident) (vidxIdList.getArg(0));
    result = Xcons.Set(indVarId.Ref(), result);

    return Bcons.Statement(result);
  }

  // make host code to launch the kernel
  Block makeLaunchFuncBlock(String launchFuncName, XobjectDef deviceKernelDef) {
    XobjList deviceKernelCallArgs = Xcons.List();
    BlockListBuilder blockListBuilder = new BlockListBuilder();
    XobjList confDecl = gpuManager.getBlockThreadSize();

    //# of block and thread
    Ident funcId = ACCutil.getMacroFuncId("_ACC_adjust_num_gangs", Xtype.voidType);
    Xobject num_gangs_decl = funcId.Call(Xcons.List(
                                                    confDecl.getArg(0),
                                                    Xcons.IntConstant(ACC.device.getMaxNumGangs())));
    Ident num_gangs = blockListBuilder.declLocalIdent("_ACC_num_gangs", Xtype.intType, num_gangs_decl);
    Ident num_workers = blockListBuilder.declLocalIdent("_ACC_num_workers", Xtype.intType, confDecl.getArg(1));
    Ident vec_len = blockListBuilder.declLocalIdent("_ACC_vec_len", Xtype.intType, confDecl.getArg(2));

    Ident mpool = Ident.Local("_ACC_mpool", Xtype.voidPtrType);
    Ident mpoolPos = Ident.Local("_ACC_mpool_pos", Xtype.longlongType);

    if (!allocList.isEmpty() || !_useMemPoolOuterIdSet.isEmpty()) {
      Block initMpoolPos = Bcons.Statement(Xcons.Set(mpoolPos.Ref(), Xcons.LongLongConstant(0, 0)));
      Block getMpoolFuncCall;
      Xobject asyncExpr = _kernelInfo.getIntExpr(ACCpragma.ASYNC);
      if (asyncExpr == null) {
        asyncExpr = Xcons.IntConstant(ACC.ACC_ASYNC_SYNC);
      }
      getMpoolFuncCall = ACCutil.createFuncCallBlock("_ACC_mpool_get_async", Xcons.List(mpool.getAddr(), asyncExpr));
      blockListBuilder.addIdent(mpool);
      blockListBuilder.addIdent(mpoolPos);
      blockListBuilder.addInitBlock(initMpoolPos);
      blockListBuilder.addInitBlock(getMpoolFuncCall);
    }

    XobjList reductionKernelCallArgs = Xcons.List();
    int reductionKernelVarCount = 0;

    for (ACCvar var : _outerVarList) {
      Ident varId = var.getId();
      Xobject paramRef;
      if (_useMemPoolOuterIdSet.contains(varId)) {
        Ident devPtrId = blockListBuilder.declLocalIdent("_ACC_dev_" + varId.getName(), Xtype.voidPtrType);
        Xobject size = var.getSize();

        Block mpoolAllocFuncCall = ACCutil.createFuncCallBlock(ACC_MPOOL_ALLOC_FUNCNAME, Xcons.List(devPtrId.getAddr(), size, mpool.Ref(), mpoolPos.getAddr()));
        Block mpoolFreeFuncCall = ACCutil.createFuncCallBlock(ACC_MPOOL_FREE_FUNCNAME, Xcons.List(devPtrId.Ref(), mpool.Ref()));
        Block HtoDCopyFuncCall = ACCutil.createFuncCallBlock("_ACC_copy_async", Xcons.List(varId.getAddr(), devPtrId.Ref(), size, Xcons.IntConstant(400), getAsyncExpr()));
        Block DtoHCopyFuncCall = ACCutil.createFuncCallBlock("_ACC_copy_async", Xcons.List(varId.getAddr(), devPtrId.Ref(), size, Xcons.IntConstant(401), getAsyncExpr()));
        blockListBuilder.addInitBlock(mpoolAllocFuncCall);
        blockListBuilder.addInitBlock(HtoDCopyFuncCall);
        blockListBuilder.addFinalizeBlock(DtoHCopyFuncCall);
        blockListBuilder.addFinalizeBlock(mpoolFreeFuncCall);
        paramRef = Xcons.Cast(Xtype.Pointer(varId.Type()), devPtrId.Ref());
      } else {
        paramRef = makeLaunchFuncArg(var);
      }

      deviceKernelCallArgs.add(paramRef);
      {
        Reduction red = reductionManager.findReduction(varId);
        if (red != null && red.needsExternalReduction()) {
          reductionKernelCallArgs.add(paramRef);
          reductionKernelVarCount++;
        }
      }
    }

    for (XobjList xobjList : allocList) {
      Ident varId = (Ident) (xobjList.getArg(0));
      Xobject baseSize = xobjList.getArg(1);
      Xobject numBlocksFactor = xobjList.getArg(2);

      Ident devPtrId = blockListBuilder.declLocalIdent("_ACC_gpu_device_" + varId.getName(), Xtype.voidPtrType);
      deviceKernelCallArgs.add(devPtrId.Ref());
      if (varId.getName().equals(ACC_REDUCTION_TMP_VAR)) {
        reductionKernelCallArgs.add(devPtrId.Ref());
      }

      Xobject size = Xcons.binaryOp(Xcode.PLUS_EXPR, baseSize,
                                    Xcons.binaryOp(Xcode.MUL_EXPR, numBlocksFactor, num_gangs.Ref()));
      Block mpoolAllocFuncCall = ACCutil.createFuncCallBlock(ACC_MPOOL_ALLOC_FUNCNAME, Xcons.List(devPtrId.getAddr(), size, mpool.Ref(), mpoolPos.getAddr()));
      Block mpoolFreeFuncCall = ACCutil.createFuncCallBlock(ACC_MPOOL_FREE_FUNCNAME, Xcons.List(devPtrId.Ref(), mpool.Ref()));
      blockListBuilder.addInitBlock(mpoolAllocFuncCall);
      blockListBuilder.addFinalizeBlock(mpoolFreeFuncCall);
    }

    //add blockReduction cnt & tmp
    if (reductionManager.hasUsingTmpReduction()) {
      Ident blockCountId = blockListBuilder.declLocalIdent("_ACC_gpu_block_count", Xtype.Pointer(Xtype.unsignedType));
      deviceKernelCallArgs.add(blockCountId.Ref());
      Block getBlockCounterFuncCall;
      Xobject asyncExpr = _kernelInfo.getIntExpr(ACCpragma.ASYNC);
      if (asyncExpr != null) {
        getBlockCounterFuncCall = ACCutil.createFuncCallBlock("_ACC_gpu_get_block_count_async", Xcons.List(blockCountId.getAddr(), asyncExpr));
      } else {
        getBlockCounterFuncCall = ACCutil.createFuncCallBlock("_ACC_gpu_get_block_count", Xcons.List(blockCountId.getAddr()));
      }
      blockListBuilder.addInitBlock(getBlockCounterFuncCall);
    }

    Block kernelLauchBlock = Bcons.emptyBlock();
    String kernelName = deviceKernelDef.getName();
    Ident kernelConf =blockListBuilder.declLocalIdent("_ACC_conf", Xtype.Array(Xtype.intType, null), Xcons.List(num_gangs.Ref(), num_workers.Ref(), vec_len.Ref()));
    kernelLauchBlock = makeKernelLaunchBlock(launchFuncName, kernelName, deviceKernelCallArgs, kernelConf, getAsyncExpr());
    blockListBuilder.add(kernelLauchBlock);

    /* execute reduction Ops on tempoary after executing main kernel */
    /* generate Launch kernel call on host side */
    if (reductionManager.hasUsingTmpReduction()) {
      XobjectDef reductionKernelDef = reductionManager.makeReductionKernelDef(launchFuncName + "_red" + ACC_GPU_DEVICE_FUNC_SUFFIX);

      // System.out.println("reductionKernelDef="+reductionKernelDef);

      XobjectFile devEnv = _decl.getEnvDevice();
      devEnv.add(reductionKernelDef);
      reductionKernelCallArgs.add(num_gangs.Ref());

      Block reductionKernelCallBlock = Bcons.emptyBlock();

      BlockList body = Bcons.emptyBody();
      kernelName = reductionKernelDef.getName();
      kernelConf = body.declLocalIdent("_ACC_conf", Xtype.Array(Xtype.intType, null),
                                       StorageClass.AUTO,
                                       Xcons.List(num_gangs.Ref(), num_workers.Ref(), vec_len.Ref()));
      body.add(makeKernelLaunchBlock(ACC_CL_KERNEL_LAUNCHER_NAME, kernelName,
                                     reductionKernelCallArgs, kernelConf, getAsyncExpr()));
      reductionKernelCallBlock = Bcons.COMPOUND(body);

      Block ifBlock = Bcons.IF(Xcons.binaryOp(Xcode.LOG_GT_EXPR, num_gangs.Ref(), Xcons.IntConstant(1)),
                               reductionKernelCallBlock, null);
      blockListBuilder.add(ifBlock);
    }

    if (!_kernelInfo.hasClause(ACCpragma.ASYNC)) {
      blockListBuilder.addFinalizeBlock(ACCutil.createFuncCallBlock("_ACC_gpu_wait", Xcons.List(Xcons.IntConstant(ACC.ACC_ASYNC_SYNC) /*_accAsyncSync*/)));
    }

    BlockList launchFuncBody = blockListBuilder.build();

    return Bcons.COMPOUND(launchFuncBody);
  }

  Block makeLauncherFuncCallCUDA(String launchFuncName, XobjectDef deviceKernelDef, XobjList deviceKernelCallArgs, Xobject num_gangs, Xobject num_workers, Xobject vec_len, Xobject asyncExpr) {
    Xobject const1 = Xcons.IntConstant(1);
    BlockList body = Bcons.emptyBody();
    XobjList conf = Xcons.List(num_gangs, const1, const1, vec_len, num_workers, const1);
    Ident confId = body.declLocalIdent("_ACC_conf", Xtype.Array(Xtype.intType, 6), StorageClass.AUTO, conf);
    XobjectDef launcherFuncDef = makeLauncherFuncDefCUDA(launchFuncName, deviceKernelDef, deviceKernelCallArgs);
    XobjectFile devEnv = _decl.getEnvDevice();
    devEnv.add(launcherFuncDef);

    Ident launcherFuncId = _decl.declExternIdent(launcherFuncDef.getName(), Xtype.Function(Xtype.voidType));
    XobjList callArgs = Xcons.List();
    for(Xobject arg : deviceKernelCallArgs){
      if(arg.Opcode() == Xcode.CAST_EXPR && arg.Type().isArray()){
        arg = Xcons.Cast(Xtype.Pointer(arg.Type().getRef()), arg.getArg(0));
      }
      callArgs.add(arg);
    }
    callArgs.add(confId.Ref());
    callArgs.add(asyncExpr);

    body.add(Bcons.Statement(launcherFuncId.Call(callArgs)));

    return Bcons.COMPOUND(body);
  }


  XobjectDef makeLauncherFuncDefCUDA(String launchFuncName, XobjectDef deviceKernelDef, XobjList deviceKernelCallArgs) {
    XobjList launcherFuncParamIds = Xcons.IDList();
    BlockList launcherFuncBody = Bcons.emptyBody();
    XobjList args = Xcons.List();
    for(Xobject arg : deviceKernelCallArgs){
      Xtype type = arg.Type();
      if(arg.Opcode() == Xcode.CAST_EXPR){
        arg = arg.getArg(0);
      }
      String varName = arg.getName();
      Ident id = Ident.Param(varName, type);
      launcherFuncParamIds.add(id);

      args.add(arg);
    }

    //confs
    int numConfs = 6;
    Ident confParamId = Ident.Param("_ACC_conf", Xtype.Array(Xtype.intType, numConfs));
    launcherFuncParamIds.add(confParamId);
    XobjList confList = Xcons.List();
    for(int i = 0; i < numConfs; i++) {
      confList.add(Xcons.arrayRef(Xtype.intType, confParamId.getAddr(), Xcons.List(Xcons.IntConstant(i))));
    }

    //asyncnum
    Ident asyncId = Ident.Param("_ACC_async_num", Xtype.intType);
    launcherFuncParamIds.add(asyncId);
    Xobject asyncExpr = asyncId.Ref();

    Ident deviceKernelId = (Ident) deviceKernelDef.getNameObj();
    Block callBlock = makeKernelLaunchBlockCUDA(deviceKernelId, args, confList, asyncExpr);
    launcherFuncBody.add(callBlock);

    Ident launcherFuncId = _decl.getEnvDevice().declGlobalIdent(launchFuncName, Xtype.Function(Xtype.voidType));
    ((FunctionType) launcherFuncId.Type()).setFuncParamIdList(launcherFuncParamIds);

    return XobjectDef.Func(launcherFuncId, launcherFuncParamIds, null, launcherFuncBody.toXobject());
  }

  Xtype makeConstArray(ArrayType at){
    if(at.getRef().isArray()){
      ArrayType ret_t = (ArrayType)(at.copy());
      ret_t.setRef(makeConstArray((ArrayType)(at.getRef())));
      return ret_t;
    } else {
      Xtype ret_t = at.copy();
      Xtype new_ref = at.getRef().copy();
      new_ref.setIsConst(true);
      ret_t.setRef(new_ref);
      return ret_t;
    }
  }

  Ident makeParamId_new(Ident id) {
    String varName = id.getName();

    // System.out.println("makeParamId_new() [varName = " + varName + "]");

    switch (id.Type().getKind()) {
    case Xtype.ARRAY: {
      Xtype t = id.Type().copy();
      if(_readOnlyOuterIdSet.contains(id)){
        t = makeConstArray((ArrayType)t);
      }
      return Ident.Local(varName, t);
    }
    case Xtype.POINTER: {
      Xtype t = id.Type().copy();
      if(_readOnlyOuterIdSet.contains(id)) t.setIsConst(true);
      return Ident.Local(varName, t);
    }
    case Xtype.BASIC:
    case Xtype.STRUCT:
    case Xtype.UNION:
    case Xtype.ENUM: {
      // check whether id is firstprivate!
      ACCvar var = _kernelInfo.findACCvar(ACCpragma.FIRSTPRIVATE, varName);
      if (var == null /*kernelInfo.isVarAllocated(varName)*/ || _useMemPoolOuterIdSet.contains(id) || var.getDevicePtr() != null) {
        Xtype t = id.Type().copy();
        if(_readOnlyOuterIdSet.contains(id)) t.setIsConst(true);
        return Ident.Local(varName, Xtype.Pointer(t));
      } else {
        return Ident.Local(varName, id.Type());
      }
    }
    default:
      ACC.fatal("unknown type");
      return null;
    }
  }

  Ident makeParamId(Ident id) {
    if(!(id.Type().getKind() == Xtype.BASIC)) {
      return null;
    }

    String varName = varName = "_ACC_PARAM_" + id.getName(); // test
    // System.out.println("makeParamId() [varName = " + varName + "]");

    // check whether id is firstprivate!
    ACCvar var = _kernelInfo.findACCvar(ACCpragma.FIRSTPRIVATE, varName);
    if (var == null /*kernelInfo.isVarAllocated(varName)*/ || _useMemPoolOuterIdSet.contains(id) || var.getDevicePtr() != null) {
      Xtype t = id.Type().copy();
      if(_readOnlyOuterIdSet.contains(id)) t.setIsConst(true);
        return Ident.Local(varName, Xtype.Pointer(t));
    } else {
      return Ident.Local(varName, id.Type());
    }
  }

  public void analyze() {
    gpuManager.analyze();

    //get outerId set
    Set<Ident> outerIdSet = new LinkedHashSet<Ident>();
    OuterIdCollector outerIdCollector = new OuterIdCollector();
    for (Block b : _kernelBlocks) {
      outerIdSet.addAll(outerIdCollector.collect(b));
    }

    //collect read only id
    _readOnlyOuterIdSet = new LinkedHashSet<Ident>(outerIdSet);
    AssignedIdCollector assignedIdCollector = new AssignedIdCollector();
    for (Block b : _kernelBlocks) {
      _readOnlyOuterIdSet.removeAll(assignedIdCollector.collect(b));
    }

    //make outerId list
    _outerIdList = new ArrayList<Ident>(outerIdSet);

    //FIXME
    for (Ident id : _outerIdList) {
      ACCvar var = _kernelInfo.findACCvar(id.getSym());
      if (var == null) continue;
      if (var.isReduction()) {
        ACCvar parentVar = findParentVar(id);
        if (parentVar == null) {
          _useMemPoolOuterIdSet.add(id);
        }
      }
    }
  }

  //copied
  ACCvar findParentVar(Ident varId) {
    if (_pb != null) {
      for (Block b = _pb.getParentBlock(); b != null; b = b.getParentBlock()) {
        // if (b.Opcode() != Xcode.ACC_PRAGMA) continue;
        AccDirective directive = (AccDirective) b.getProp(AccDirective.prop);
        if(directive == null) continue;
        AccInformation info = directive.getInfo();
        ACCvar var = info.findACCvar(varId.getSym());
        if (var != null && var.getId() == varId) {
          return var;
        }
      }
    }

    return null;
  }

  void replaceVarInDecls(Block b, Ident fromId, Ident toId, Block block, XobjList decls) {
    if(decls == null) return;
    for(Xobject decl : decls){
      Xobject declRight = decl.right();
      decl.setRight(replaceVar(b, fromId, toId, declRight, block));
    }
  }

  Xobject replaceVar(Block b, Ident fromId, Ident toId, Xobject expr, Block parentBlock) {
    // System.out.println("replaceVar() [expr = " + expr.toString() + " ]");
    // System.out.println("replaceVar() [parentBlock = " + parentBlock.toString() + " ]");

    topdownXobjectIterator exprIter = new topdownXobjectIterator(expr);
    for (exprIter.init(); !exprIter.end(); exprIter.next()) {
      Xobject x = exprIter.getXobject();
      // System.out.println("replaceVar() [x = " + x.toString() + "]");
      switch(x.Opcode()) {
      case VAR: {
        String varName = x.getName();
        if (!fromId.getName().equals(varName)) continue;
        Ident id = findInnerBlockIdent(b, parentBlock.getParent(), "_ACC_thread_x_id");

        // if (id != fromId && id != toId) continue; //if(id != fromId) continue;

        Xobject replaceXobject = null;
        if (toId.Type().equals(fromId.Type())) {
          replaceXobject = toId.Ref();
        } else if (toId.Type().isPointer() && toId.Type().getRef().equals(fromId.Type())) {
          replaceXobject = Xcons.PointerRef(toId.Ref());
        } else {
          ACC.fatal("unexpected fromId or toId type");
        }
        if (expr == x) return replaceXobject;
        exprIter.setXobject(replaceXobject);
        break;
      }
      case VAR_ADDR: {
        String varName = x.getName();
        if (!fromId.getName().equals(varName)) continue;
        Ident id = findInnerBlockIdent(b, parentBlock.getParent(), "_ACC_thread_x_id");

        if (id != fromId && id != toId) continue; //if(id != fromId) continue;

        Xobject replaceXobject = null;
        if (toId.Type().equals(fromId.Type())) {
          replaceXobject = toId.getAddr();
        } else if (toId.Type().isPointer() && toId.Type().getRef().equals(fromId.Type())) {
          replaceXobject = toId.Ref();
        } else {
          ACC.fatal("unexpected fromId or toId type");
        }
        if (expr == x) return replaceXobject;
        exprIter.setXobject(replaceXobject);
        break;
      }
      }
    }
    return expr;
  }

  public void setReadOnlyOuterIdSet(Set<Ident> readOnlyOuterIdSet) {
    _readOnlyOuterIdSet = readOnlyOuterIdSet;
  }

  public Set<Ident> getReadOnlyOuterIdSet() {
    return _readOnlyOuterIdSet;
  }

  public Set<Ident> getOuterIdSet() {
    return new LinkedHashSet<Ident>(_outerIdList);
  }

  public List<Ident> getOuterIdList() {
    return _outerIdList;
  }

  Block makeMasterBlock(EnumSet<ACCpragma> outerParallelism, Block thenBlock){
    /*
    Xobject condition = null;

    if(outerParallelism.contains(ACCpragma.VECTOR)) {
      return thenBlock;
    }else if(outerParallelism.contains(ACCpragma.WORKER)){
      condition = Xcons.binaryOp(Xcode.LOG_EQ_EXPR, _accThreadIndexY, Xcons.IntConstant(0));
    }else if(outerParallelism.contains(ACCpragma.GANG)){
      condition = Xcons.binaryOp(Xcode.LOG_EQ_EXPR, _accThreadIndex, Xcons.IntConstant(0));
    }else{
      condition = Xcons.binaryOp(Xcode.LOG_EQ_EXPR, _accThreadIndex, Xcons.IntConstant(0));
    }

    return Bcons.IF(condition, thenBlock, null);
    */

    return thenBlock;
  }

  Block makeSyncBlock(EnumSet<ACCpragma> outerParallelism){
    /*
    System.out.println("makeSyncBlock() [called]");
    if(outerParallelism.contains(ACCpragma.VECTOR)) {
      return Bcons.emptyBlock();
      //}else if(outerParallelism.contains(ACCpragma.WORKER)){
    }else if(outerParallelism.contains(ACCpragma.GANG)){
      return Bcons.Statement(_accSyncThreads);
    }else{
      return Bcons.Statement(_accSyncThreads);
    }
    */

    return Bcons.emptyBlock();
  }

  boolean hasInnerLoop(BlockList body) {
    if(body == null) {
      return false;
    }

    topdownXobjectIterator iter = new topdownXobjectIterator(body.toXobject());
    for(iter.init(); !iter.end(); iter.next()) {
      Xobject v = iter.getXobject();
      if(v != null) {
        Xcode vcode = v.Opcode();
        if(vcode == Xcode.FOR_STATEMENT || vcode == Xcode.WHILE_STATEMENT) {
          // System.out.println("hasInnerLoop() [v = ]" + v.toString());
          return true;
        }
      }
    }

    return false;
  }

  Xobject unrollInitializer(ACCpragma op, Xtype type) {
    switch (op) {
      case REDUCTION_PLUS:
        return unrollPlusInitializer(type);
      case REDUCTION_MUL:
        return unrollMulInitializer(type);
      case REDUCTION_MIN:
        return unrollMinInitializer(type);
      case REDUCTION_MAX:
        return unrollMaxInitializer(type);
      case REDUCTION_BITAND:
        return Xcons.Int(Xcode.BIT_NOT_EXPR, 0);
      case REDUCTION_BITOR:
        return Xcons.IntConstant(0);
      case REDUCTION_BITXOR:
        return Xcons.IntConstant(0);
      case REDUCTION_LOGAND:
        return Xcons.IntConstant(1);
      case REDUCTION_LOGOR:
        return Xcons.IntConstant(0);
      default:
        return null;
    }
  }

  Xobject unrollPlusInitializer(Xtype type) {
    switch(type.getBasicType()) {
      case BasicType.SHORT:
      case BasicType.UNSIGNED_SHORT:
      case BasicType.INT:
      case BasicType.UNSIGNED_INT:
      case BasicType.LONG:
      case BasicType.UNSIGNED_LONG:
        return Xcons.IntConstant(0);
      case BasicType.FLOAT:
      case BasicType.DOUBLE:
        return Xcons.FloatConstant(0.);
      case BasicType.LONGLONG:
      case BasicType.UNSIGNED_LONGLONG:
      case BasicType.LONG_DOUBLE:
      default:
        return null;
    }
  }

  Xobject unrollMulInitializer(Xtype type) {
    switch(type.getBasicType()) {
      case BasicType.SHORT:
      case BasicType.UNSIGNED_SHORT:
      case BasicType.INT:
      case BasicType.UNSIGNED_INT:
      case BasicType.LONG:
      case BasicType.UNSIGNED_LONG:
        return Xcons.IntConstant(1);
      case BasicType.FLOAT:
      case BasicType.DOUBLE:
        return Xcons.FloatConstant(1.);
      case BasicType.LONGLONG:
      case BasicType.UNSIGNED_LONGLONG:
      case BasicType.LONG_DOUBLE:
      default:
        return null;
    }
  }

  Xobject unrollMinInitializer(Xtype type) {
    switch(type.getBasicType()) {
      case BasicType.SHORT:
        return Ident.Local("SHRT_MIN", Xtype.shortType);
      case BasicType.UNSIGNED_SHORT:
        return Xcons.IntConstant(0);
      case BasicType.INT:
        return Ident.Local("INT_MIN", Xtype.intType);
      case BasicType.UNSIGNED_INT:
        return Xcons.IntConstant(0);
      case BasicType.LONG:
        return Ident.Local("LONG_MIN", Xtype.longType);
      case BasicType.UNSIGNED_LONG:
        return Xcons.IntConstant(0);
      case BasicType.FLOAT:
        return Ident.Local("FLT_MIN", Xtype.floatType);
      case BasicType.DOUBLE:
        return Ident.Local("DBL_MIN", Xtype.doubleType);
      case BasicType.LONGLONG:
      case BasicType.UNSIGNED_LONGLONG:
      case BasicType.LONG_DOUBLE:
      default:
        return null;
    }
  }

  Xobject unrollMaxInitializer(Xtype type) {
    switch(type.getBasicType()) {
      case BasicType.SHORT:
        return Ident.Local("SHRT_MAX", Xtype.shortType);
      case BasicType.UNSIGNED_SHORT:
        return Ident.Local("USHRT_MAX", Xtype.unsignedshortType);
      case BasicType.INT:
        return Ident.Local("INT_MAX", Xtype.intType);
      case BasicType.UNSIGNED_INT:
        return Ident.Local("UINT_MAX", Xtype.unsignedType);
      case BasicType.LONG:
        return Ident.Local("LONG_MAX", Xtype.longType);
      case BasicType.UNSIGNED_LONG:
        return Ident.Local("ULONG_MAX", Xtype.unsignedlongType);
      case BasicType.FLOAT:
        return Ident.Local("FLT_MAX", Xtype.floatType);
      case BasicType.DOUBLE:
        return Ident.Local("DBL_MAX", Xtype.doubleType);
      case BasicType.LONGLONG:
      case BasicType.UNSIGNED_LONGLONG:
      case BasicType.LONG_DOUBLE:
      default:
        return null;
    }
  }

  Xobject makePartReduction(ACCpragma op, Ident baseId, Ident partId) {
    return makePartReduction(op, baseId.Type(), baseId.Ref(), partId.Ref());
  }

  Xobject makePartReduction(ACCpragma op, Xtype type, Xobject left, Xobject right) {
    switch (op) {
      case REDUCTION_PLUS:
        return Xcons.List(Xcode.ASG_PLUS_EXPR, type, left, right);
      case REDUCTION_MUL:
        return Xcons.List(Xcode.ASG_MUL_EXPR, type, left, right);
      case REDUCTION_MIN:
        return Xcons.Set(left, Xcons.List(Xcode.CONDITIONAL_EXPR, type, Xcons.List(Xcode.LOG_LT_EXPR, type, left, right), Xcons.List(left, right)));
      case REDUCTION_MAX:
        return Xcons.Set(left, Xcons.List(Xcode.CONDITIONAL_EXPR, type, Xcons.List(Xcode.LOG_GT_EXPR, type, left, right), Xcons.List(left, right)));
      case REDUCTION_BITAND:
        return Xcons.List(Xcode.ASG_BIT_AND_EXPR, type, left, right);
      case REDUCTION_BITOR:
        return Xcons.List(Xcode.ASG_BIT_OR_EXPR, type, left, right);
      case REDUCTION_BITXOR:
        return Xcons.List(Xcode.ASG_BIT_XOR_EXPR, type, left, right);
      case REDUCTION_LOGAND:
        return Xcons.Set(left, Xcons.List(Xcode.LOG_AND_EXPR, type, left, right));
      case REDUCTION_LOGOR:
        return Xcons.Set(left, Xcons.List(Xcode.LOG_OR_EXPR, type, left, right));
      default:
        return null;
    }
  }

  void setOffset(Block b, Ident baseId, int i) {
    BasicBlockExprIterator iter = new BasicBlockExprIterator(b);
    for (iter.init(); !iter.end(); iter.next()) {
      Xobject expr = iter.getExpr();
      setOffset(baseId, i, expr);
    }
  }

  Xobject setOffset(Ident baseId, int offset, Xobject expr) {
    // System.out.println("setOffset() [expr = " + expr.toString() + " ]");
    // System.out.println("setOffset() [parentBlock = " + parentBlock.toString() + " ]");
    // System.out.println("setOffset() [baseId = " + baseId.getName() + " ]");
    topdownXobjectIterator exprIter = new topdownXobjectIterator(expr);
    for (exprIter.init(); !exprIter.end(); exprIter.next()) {
      Xobject x = exprIter.getXobject();
      // System.out.println("setOffset() [x = " + x.toString() + "]");
      switch(x.Opcode()) {
        case VAR:
        case VAR_ADDR: {
          String varName = x.getName();
          // System.out.println("setOffset() [varName = " + varName + " ]");
          if (!baseId.getName().equals(varName)) continue;

          Xobject replaceXobject = Xcons.binaryOp(Xcode.PLUS_EXPR, baseId.Ref(), Xcons.IntConstant(offset));

          if (expr == x) return replaceXobject;
          // System.out.println("setOffset() [replaceXobject = " + replaceXobject.toString() + " ]");
          exprIter.setXobject(replaceXobject);
          break;
        }
      }
    }
    return expr;
  }

  void setOffsetInDecl(Block b, Ident id, int i, Set<String> replacedDecl) {
    if(b == null) {
      return;
    }

    for(Block bc = b; bc != null; bc = bc.getNext()) {
      BlockList list = bc.getBody();
      if(list != null) {
        Xobject decl = list.getDecls();

        for(Block inner = list.getHead(); inner != null; inner = inner.getNext()) {
          setOffsetInDecl(inner, id, i, replacedDecl);
        }
        if(decl != null) {
          Xobject dc = decl.copy();
          // System.out.println("kernel " + fpgaKernelNum +":list = " + list);
          for(int j = 0; j < dc.Nargs(); j++) {
            String name = dc.getArg(j).getArg(0).getName();
            if(!replacedDecl.contains(name)) {
              setOffset(id, i, dc.getArg(j).getArg(1));
              replacedDecl.add(name);
              // System.out.println("kernel " + fpgaKernelNum +":dc.getArg(" + j + ") = " + dc.getArg(j));
            }
          }
          // System.out.println("kernel " + fpgaKernelNum +":decl = " + decl);
          // System.out.println("kernel " + fpgaKernelNum +":dc = " + dc);
          list.setDecls(dc);
        }
      }
    }
  }

  void assignConstInt(Block b, Ident baseId, int i) {
    BasicBlockExprIterator iter = new BasicBlockExprIterator(b);
    for (iter.init(); !iter.end(); iter.next()) {
      Xobject expr = iter.getExpr();
      assignConstInt(baseId, i, expr);
    }
  }

  Xobject assignConstInt(Ident baseId, int cint, Xobject expr) {
    topdownXobjectIterator exprIter = new topdownXobjectIterator(expr);
    for (exprIter.init(); !exprIter.end(); exprIter.next()) {
      Xobject x = exprIter.getXobject();
      switch(x.Opcode()) {
        case VAR:
        case VAR_ADDR: {
          String varName = x.getName();
          if (!baseId.getName().equals(varName)) continue;

          Xobject replaceXobject = Xcons.IntConstant(cint);

          if (expr == x) return replaceXobject;
          exprIter.setXobject(replaceXobject);
          break;
        }
      }
    }
    return expr;
  }

  void assignConstIntInDecl(Block b, Ident id, int i) {
    if(b == null) {
      return;
    }

    for(Block bc = b; bc != null; bc = bc.getNext()) {
      BlockList list = bc.getBody();
      if(list != null) {
        Xobject decl = list.getDecls();

        for(Block inner = list.getHead(); inner != null; inner = inner.getNext()) {
          assignConstIntInDecl(inner, id, i);
        }
        if(decl != null) {
          Xobject dc = decl.copy();
          for(int j = 0; j < dc.Nargs(); j++) {
            assignConstInt(id, i, dc.getArg(j).getArg(1));
          }
          list.setDecls(dc);
        }
      }
    }
  }


  void replaceArrayPointer(Block b, Ident fromId, Ident toId) {
    BasicBlockExprIterator iter = new BasicBlockExprIterator(b);
    for (iter.init(); !iter.end(); iter.next()) {
      Xobject expr = iter.getExpr();
      replaceArrayPointer(fromId, toId, expr);
    }
  }

  Xobject replaceArrayPointer(Ident fromId, Ident toId, Xobject expr) {

    topdownXobjectIterator exprIter = new topdownXobjectIterator(expr);

    for (exprIter.init(); !exprIter.end(); exprIter.next()) {
      Xobject x = exprIter.getXobject();

        if(fpgaKernelNum == 0) {
          // System.out.println("x = " + x);
        }
      switch(x.Opcode()) {
      case VAR:
      case ARRAY_ADDR:{
        String varName = x.getName();

        if (!fromId.getName().equals(varName)) continue;

        Xobject replaceXobject = null;
        if (toId.Type().equals(fromId.Type())) {
          replaceXobject = toId.Ref();
        } else if (toId.Type().isPointer() && toId.Type().getRef().equals(fromId.Type())) {
          replaceXobject = Xcons.PointerRef(toId.Ref());
        } else {
          Xtype ft = fromId.Type();
          Xtype tt = toId.Type();
          int fromDim = 0;
          int toDim = 0;

          while(ft.isArray() || ft.isPointer()) {
            if(ft.isArray()) {
              ft = ((ArrayType)ft).getRef();
            } else {
              ft = ((PointerType)ft).getRef();
            }
            fromDim++;
          }

          while(tt.isArray() || tt.isPointer()) {
            if(tt.isArray()) {
              tt = ((ArrayType)tt).getRef();
            } else {
              tt = ((PointerType)tt).getRef();
            }
            toDim++;
          }

          if(fromDim == toDim && ft.equals(tt)) {
            replaceXobject = toId.Ref();
          } else {
            ACC.fatal("unexpected fromId or toId type");
          }
        }
        if (expr == x) return replaceXobject;
        exprIter.setXobject(replaceXobject);
        break;
      }
      }
    }
    return expr;
  }

  boolean isMulkerArrayOnBram(String s) {
    for(ACCvar var : onBramList) {
      if(var.getName().equals(s)) {
        if(var.isDivide() || var.isShadow() || var.isAlign()) {
          return true;
        }
      }
    }

    return false;
  }

  ACCvar getArrayOnBram(String s) {
    for(ACCvar var : onBramList) {
      if(var.getName().equals(s)) {
        if(var.isDivide() || var.isShadow() || var.isAlign()) {
          return var;
        }
      }
    }

    return null;
  }

  ACCvar findArrayOnBram(BlockList b, Ident loopIter) {
    BasicBlockExprIterator iter = new BasicBlockExprIterator(b);
    for (iter.init(); !iter.end(); iter.next()) {
      Xobject expr = iter.getExpr();

      ACCvar var = findArrayOnBram(loopIter, expr);
      if(var != null) {
        return var;
      }
    }

    return null;
  }

  ACCvar findArrayOnBram(Ident loopIter, Xobject expr) {
    topdownXobjectIterator exprIter = new topdownXobjectIterator(expr);

    for(exprIter.init(); !exprIter.end(); exprIter.next()) {
      Xobject x = exprIter.getXobject();
      switch(x.Opcode()) {
        case ARRAY_REF: {
          String name = x.getArg(0).getName();
          // System.out.println("findArrayOnBram() [name = " + name + "]");
          Xobject index = x.getArg(1);
          if(index.getArg(0).equals(loopIter.Ref())) {
            // a[loopIter]
            // System.out.println("arg(1) = " + x.getArg(1));
            for(ACCvar var : onBramList) {
              if(var.getName().equals(name)) {
                if(var.isDivide() || var.isShadow() || var.isAlign()) {
                  return var;
                }
              }
            }
          }
          if(index.Nargs() == 1 && index.Opcode() == Xcode.PLUS_EXPR) {
            for(ACCvar var : onBramList) {
              if(var.getName().equals(name)) {
                if(var.isDivide() || var.isShadow() || var.isAlign()) {
                  Xobject loopx = findIterBinaryMul(index, loopIter);
                  if(loopx != null) {
                    ArrayList<Integer> list = dimList(loopx, loopIter);
                    ArrayList<Integer> args = new ArrayList<Integer>();

                    if(list != null && list.size() == var.getDim()) {
                      XobjList subscripts = var.getSubscripts();
                      for(int i = 0; i < var.getDim() - 1; i++) {
                        args.add(calcXobject(subscripts.getArg(i)));
                      }
                      args.add(0);

                      if(list.containsAll(args)) {
                        return var;
                      }
                    }
                  }
                }
              }
            }
          }
        }
        break;
        case POINTER_REF: {
          // System.out.println("findArrayOnBram() [x = " + x + "]");
          Xobject arg = findBramPointerRefBinaryOp(x.getArg(0));

          if(arg != null) {
            // System.out.println("findArrayOnBram() [arg = " + arg + "]");
            if(arg.left().equals(loopIter.Ref())) {
              for(ACCvar var : onBramList) {
                if(var.getName().equals(arg.right().getName())) {
                  if(var.isDivide() || var.isShadow() || var.isAlign()) {
                    return var;
                  }
                }
              }
            } else if(arg.right().equals(loopIter.Ref())) {
              for(ACCvar var : onBramList) {
                if(var.getName().equals(arg.left().getName())) {
                  if(var.isDivide() || var.isShadow() || var.isAlign()) {
                    return var;
                  }
                }
              }
            } else {
              // not *(p + loopIter) nor *(loopIter + p)
              continue;
            }
          }
        }
      }
    }

    return null;
  }

  Xobject findIterBinaryMul(Xobject x, Ident loopIter) {
    if(x.Opcode() == Xcode.MUL_EXPR) {
      if(x.findVarIdent(loopIter.getName()) != null) {
        return x;
      } else {
        return null;
      }
    }

    if(x.Opcode() == Xcode.PLUS_EXPR) {
      Xobject xl = x.left();
      Xobject xr = x.right();

      if(xl.findVarIdent(loopIter.getName()) != null) {
        if(xr.findVarIdent(loopIter.getName()) != null) {
          return null;
        } else {
          return findIterBinaryMul(xl, loopIter);
        }
      }
      if(xr.findVarIdent(loopIter.getName()) != null) {
        return findIterBinaryMul(xr, loopIter);
      }
    }

    return null;
  }

  ArrayList<Integer> dimList(Xobject x, Ident loopIter) {
    ArrayList<Integer> list = new ArrayList<Integer>();
    if(x.Opcode() == Xcode.VAR) {
      if(x.getName().equals(loopIter.getName())) {
        list.add(0);
        return list;
      }
      return null;
    }

    if(x.Opcode() == Xcode.PLUS_EXPR || x.Opcode() == Xcode.MINUS_EXPR || x.Opcode() == Xcode.DIV_EXPR) {
      if(isCalculatable(x)) {
        list.add(calcXobject(x));
        return list;
      }
      return null;
    }

    if(x.Opcode() != Xcode.MUL_EXPR) {
      return null;
    }

    Xobject xl = x.left();
    Xobject xr = x.right();
    ArrayList<Integer> listL = dimList(xl, loopIter);
    ArrayList<Integer> listR = dimList(xr, loopIter);

    if(listL == null || listR == null) {
      return null;
    }

    list.addAll(listL);
    list.addAll(listR);

    return list;
  }

  Xobject findBramPointerRefBinaryOp(Xobject x) {
    // System.out.println("findVarOrAddrXobject() [x = " + x +"]");
    if(x.Opcode() != Xcode.PLUS_EXPR && x.Opcode() != Xcode.MINUS_EXPR) {
      return null;
    }

    Xobject xl = x.left();
    Xobject xr = x.right();
    Xobject resultL = null;
    Xobject resultR = null;

    if(xl.Opcode() == Xcode.VAR || xl.Opcode() == Xcode.ARRAY_ADDR) {
      for(ACCvar var : onBramList) {
        if(var.getName().equals(xl.getName())) {
          resultL = x;
          break;
        }
      }
    } else {
      resultL = findBramPointerRefBinaryOp(xl);
    }

    if(xr.Opcode() == Xcode.VAR || xr.Opcode() == Xcode.ARRAY_ADDR) {
      for(ACCvar var : onBramList) {
        if(var.getName().equals(xr.getName())) {
          resultR = x;
          break;
        }
      }
    } else {
      resultR = findBramPointerRefBinaryOp(xr);
    }

    if(resultL != null) {
      if(resultR != null) {
        return null;
      } else {
        return resultL;
      }
    } else {
      return resultR;
    }
  }

  void setLoopArrayFrontOffset(Block b, Ident loopIter, int offset) {
    BasicBlockExprIterator iter = new BasicBlockExprIterator(b);
    for (iter.init(); !iter.end(); iter.next()) {
      Xobject expr = iter.getExpr();
      setLoopArrayFrontOffset(loopIter, expr, offset);
    }
  }

  void setLoopArrayFrontOffset(Ident loopIter, Xobject expr, int offset) {
    topdownXobjectIterator exprIter = new topdownXobjectIterator(expr);

    for(exprIter.init(); !exprIter.end(); exprIter.next()) {
      Xobject x = exprIter.getXobject();
      switch(x.Opcode()) {
        case ARRAY_REF: {
          String name = x.getArg(0).getName();
          // System.out.println("setLoopArrayFrontOffset() [name = " + name + "]");
          Xobject index = x.getArg(1);
          if(index.findVarIdent(loopIter.getName()) != null) {
            // System.out.println("arg(1) = " + x.getArg(1));
            for(ACCvar var : onBramList) {
              if(var.getName().equals(name)) {
                if(var.isDivide() || var.isShadow()) {
                  setOffset(loopIter, -offset, x);

                  if(var.isShadow() && fpgaKernelNum != 0) {
                    Xobject replaceXobject = Xcons.arrayRef(x.Type(), x.getArg(0), Xcons.List(Xcons.binaryOp(Xcode.PLUS_EXPR, x.getArg(1), Xcons.IntConstant(var.getFrontOffset()))));

                    if (expr == x) return;
                      exprIter.setXobject(replaceXobject);
                  }
                }
              }
            }
          }
        }
        break;
        case POINTER_REF: {
          // System.out.println("setLoopArrayFrontOffset() [x = " + x + "]");
          ACCvar var = findBramPointerRef(x.getArg(0));

          if(var == null) {
            continue;
          }else if(!var.isDivide() && !var.isShadow()) {
            continue;
          }

          if(findLoopIter(x.getArg(0), loopIter)) {
            setOffset(loopIter, -offset, x);

            if(var.isShadow() && fpgaKernelNum != 0) {
              // Xobject replaceXobject = Xcons.arrayRef(x.Type(), x.getArg(0), Xcons.List(Xcons.binaryOp(Xcode.PLUS_EXPR, x.getArg(0), Xcons.IntConstant(var.getFrontOffset()))));
              Xobject replaceXobject = Xcons.PointerRef(Xcons.binaryOp(Xcode.PLUS_EXPR, x.getArg(0), Xcons.IntConstant(var.getFrontOffset())));

              if (expr == x) return;
                exprIter.setXobject(replaceXobject);
            }
          }
        }
      }
    }
  }

/*
  void setLoopArrayFrontOffset(Ident loopIter, Xobject expr, int offset) {
    topdownXobjectIterator exprIter = new topdownXobjectIterator(expr);

    for(exprIter.init(); !exprIter.end(); exprIter.next()) {
      Xobject x = exprIter.getXobject();
      switch(x.Opcode()) {
        case ARRAY_REF: {
          String name = x.getArg(0).getName();
          // System.out.println("setLoopArrayFrontOffset() [name = " + name + "]");
          Xobject index = x.getArg(1);
          if(index.findVarIdent(loopIter.getName()) != null) {
            ACCvar a = null;
            // System.out.println("arg(1) = " + x.getArg(1));
            for(ACCvar var : onBramList) {
              if(var.getName().equals(name)) {
                if(var.isDivide() || var.isShadow()) {
                  a = var;
                  break;
                }
              }
            }
            if(a == null) {
              continue;
            }

            ArrayList<Xobject> newArgs = new ArrayList<Xobject>();
            Xobject[] dummy = new Xobject[1];
            Xobject[] argArray;

            int calcOffset = offset;

            if(a.isShadow() && fpgaKernelNum != 0) {
              calcOffset -= a.getFrontOffset();
            }

            for(int i = 1; i < x.Nargs(); i++) {
              newArgs.add(x.getArg(i));
            }
            newArgs.set(0, Xcons.binaryOp(Xcode.MINUS_EXPR, x.getArg(1), Xcons.IntConstant(calcOffset)));
            argArray = newArgs.toArray(dummy);

            Xobject replaceXobject = Xcons.arrayRef(x.Type(), x.getArg(0), Xcons.List(argArray));

            if (expr == x) return;
            exprIter.setXobject(replaceXobject);
          }
        }
        break;
        case POINTER_REF: {
          // System.out.println("setLoopArrayFrontOffset() [x = " + x + "]");
          ACCvar a = findBramPointerRef(x.getArg(0));

          if(a == null) {
            continue;
          }else if(!a.isDivide() && !a.isShadow()) {
            continue;
          }

          if(findLoopIter(x.getArg(0), loopIter)) {
            int calcOffset = offset;

            if(a.isShadow() && fpgaKernelNum != 0) {
              calcOffset -= a.getFrontOffset();
            }

            Xobject replaceXobject = Xcons.PointerRef(Xcons.binaryOp(Xcode.MINUS_EXPR, x.getArg(0), Xcons.IntConstant(calcOffset)));

            if (expr == x) return;
            exprIter.setXobject(replaceXobject);
          }
        }
      }
    }
  }

*/

  ACCvar findBramPointerRef(Xobject x) {
    if(x.Opcode() == Xcode.VAR || x.Opcode() == Xcode.ARRAY_ADDR) {
      for(ACCvar var : onBramList) {
        if(var.getName().equals(x.getName())) {
          // System.out.println("findBramVarOrAddr() [var.getName() = " + var.getName() + "]");
          return var;
        }
      }

      return null;
    }

    if(x.Opcode() != Xcode.PLUS_EXPR && x.Opcode() != Xcode.MINUS_EXPR) {
      return null;
    }

    ACCvar al = findBramPointerRef(x.left());
    ACCvar ar = findBramPointerRef(x.right());

    if(al != null) {
      if(ar != null) {
        return null;
      } else {
        return al;
      }
    } else {
      return ar;
    }
  }

  boolean findLoopIter(Xobject x, Ident loopIter) {
    if(x.Opcode() == Xcode.VAR) {
      return x.getName().equals(loopIter.getName());
    }

    if(x.Opcode() != Xcode.PLUS_EXPR && x.Opcode() != Xcode.MINUS_EXPR && x.Opcode() != Xcode.MUL_EXPR && x.Opcode() != Xcode.DIV_EXPR) {
      return false;
    }

    return (findLoopIter(x.left(), loopIter) || findLoopIter(x.right(), loopIter));
  }

  void checkExceedArray(BlockList list, ACCvar var) {
    Block b = list.getHead();
    while(b != null) {
      checkExceedArray(b, var);
      b = b.getNext();
    }
  }

  void checkExceedArray(Block b, ACCvar var) {
    if(b.Opcode() == Xcode.LIST) {
      for(Statement s : b.getBasicBlock()) {
        Xobject x = s.getExpr();
        if(x.Opcode() == Xcode.EXPR_STATEMENT) {
          // System.out.println("checkExceedArray() [x = " + x + "]");

          x = x.getArg(0);
          if(x.Opcode() == Xcode.FUNCTION_CALL) {
            XobjList args = (XobjList)x.getArgOrNull(1);
            ArrayList<Xobject> arglist = new ArrayList<Xobject>();
            boolean replace = false;
            // System.out.println("checkExceedArray() [args = " + args + "]");
            if(args == null) {
              return;
            }

            for(Xobject ax : args) {
              int found = findExceedArray(ax, var);
              if(found == fpgaKernelNum) {
                ax = adjustArrayOffset(ax, var);
                for(int i = 0; i < numKernels; i++) {
                  if(i == fpgaKernelNum) {
                    continue;
                  }

                  XobjList sender = Xcons.List(Xcode.EXPR_STATEMENT, Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_SEND_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), fpgaKernelNum, i).Ref(), ax)));
                  // System.out.println("checkExceedArray() [sender = " + sender + "]");
                  s.insert(sender);
                }

                arglist.add(ax);
                replace = true;
              } else if(found >= 0 && found < numKernels) {
                XobjList receiver = Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_RECV_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), found, fpgaKernelNum).Ref()));
                // System.out.println("checkExceedArray() [receiver = " + receiver + "]");

                arglist.add(receiver);
                replace = true;
              } else {
                arglist.add(ax);
              }
            }

            if(replace) {
              Xobject[] dummy = new Xobject[1];
              Xobject[] newArgs;

              newArgs = arglist.toArray(dummy);
              s.setExpr(Xcons.List(Xcode.EXPR_STATEMENT, Xcons.List(x.Opcode(), x.getArg(0), Xcons.List(newArgs))));
            }
          }
        } else if(x.Opcode() == Xcode.ASSIGN_EXPR || x.Opcode().isAsgOp()) {
          // System.out.println("checkExceedArray() [x = " + s.getExpr() + "]");
          Xobject xl = x.left();
          Xobject xr = x.right();
          int found = findExceedArray(xl, var);

          if(found == fpgaKernelNum) {
            xl = adjustArrayOffset(xl, var);
            s.setExpr(Xcons.List(x.Opcode(), x.Type(), xl, xr));
          } else if(found >= 0 && found < numKernels) {
            s.remove();
            continue;
          }

          found = findExceedArray(xr, var);
          // System.out.println("checkExceedArray() [found = " + found + "]");
          if(found == fpgaKernelNum) {
            xr = adjustArrayOffset(xr, var);
            for(int i = 0; i < numKernels; i++) {
              if(i == fpgaKernelNum) {
                continue;
              }

              XobjList sender = Xcons.List(Xcode.EXPR_STATEMENT, Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_SEND_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), fpgaKernelNum, i).Ref(), xr)));
              // System.out.println("checkExceedArray() [sender = " + sender + "]");
              s.insert(sender);
            }
            s.setExpr(Xcons.List(x.Opcode(), x.Type(), xl, xr));
          } else if(found >= 0 && found < numKernels) {
            XobjList receiver = Xcons.List(Xcode.FUNCTION_CALL, Xtype.Function(var.getElementType()), Xcons.Symbol(Xcode.FUNC_ADDR, ACC_CL_RECV_FUNC_NAME), Xcons.List(getKernelChannel(var.getElementType(), found, fpgaKernelNum).Ref()));
            // System.out.println("checkExceedArray() [receiver = " + receiver + "]");

            s.setExpr(Xcons.List(x.Opcode(), x.Type(), xl, receiver));
          }
        }
      }
      return;
    }
  }

  int findExceedArray(Xobject x, ACCvar var) {
    if(x.Opcode() == Xcode.ARRAY_REF) {
      if(var.getName().equals(x.getArg(0).getName())) {
        Xobject index = x.getArg(1).getArg(0);

        if(isCalculatable(index)) {
          int i = calcXobject(index);
          int num = 0;

          if(i < 0) {
            return -1;
          }

          while(i - var.getPartLength() > 0) {
            num++;
            i -= var.getPartLength();
          }
          return num;
        }
      }
    }

    if(x.Opcode() == Xcode.POINTER_REF) {
      Xobject inner = getInnerPointer(x);

      if(inner != null) {
        Xobject arg;
        if(inner.Opcode() == Xcode.ARRAY_ADDR) {
          arg = inner.copy();
        } else {
          arg = inner.getArg(0).copy();
        }
        Xobject index = assignArrayConstInt(var.getId(), 0, arg);
        if(isCalculatable(index)) {
          int i = calcXobject(index);
          int num = 0;

          if(i < 0) {
            return -1;
          }

          while(i - var.getPartLength() > 0) {
            num++;
            i -= var.getPartLength();
          }
          return num;
        }
      }
    }
    return -1;
  }

  Xobject adjustArrayOffset(Xobject x, ACCvar var) {
    if(x.Opcode() == Xcode.VAR || x.Opcode() == Xcode.ARRAY_ADDR) {
      return x;
    }

    if(x.Opcode() == Xcode.ARRAY_REF) {
      ArrayList<Xobject> list = new ArrayList<Xobject>();
      Xobject[] dummy = new Xobject[1];
      Xobject[] newSub;

      if(var.isDivide() || fpgaKernelNum == 0) {
        list.add(Xcons.binaryOp(Xcode.MINUS_EXPR, x.getArg(1).getArg(0), Xcons.IntConstant(var.getPartOffset())));
      } else {
        list.add(Xcons.binaryOp(Xcode.MINUS_EXPR, x.getArg(1).getArg(0), Xcons.IntConstant(var.getPartOffset() - var.getFrontOffset())));
      }

      for(int i = 1; i < x.getArg(1).Nargs(); i++) {
        list.add(x.getArg(1).getArg(i));
      }

      newSub = list.toArray(dummy);

      return Xcons.arrayRef(x.Type(), x.getArg(0), Xcons.List(newSub));
    }

    if(x.Opcode() == Xcode.POINTER_REF) {
      Xobject arg;

      if(var.isDivide() || fpgaKernelNum == 0) {
        arg = setArrayOffset(var.getId(), var.getPartOffset(), x.getArg(0).copy());
      } else {
        arg = setArrayOffset(var.getId(), var.getPartOffset() - var.getFrontOffset(), x.getArg(0).copy());
      }

      // System.out.println("adjustArrayOffset() [arg = " + arg + "]");

      return Xcons.PointerRef(arg);
    }

    if(x.Opcode().isAsgOp()) {
      return Xcons.binaryOp(x.Opcode(), adjustArrayOffset(x.left(), var), adjustArrayOffset(x.right(), var));
    }

    return null;
  }

  Xobject getInnerPointer(Xobject x) {
    if(x.Opcode().isTerminal()) {
      return null;
    }

    if(x.Opcode() == Xcode.POINTER_REF) {
      Xobject inner = getInnerPointer(x.getArg(0));
      if(inner == null) {
        return x;
      } else {
        return inner;
      }
    }

    if(x.Opcode() == Xcode.PLUS_EXPR || x.Opcode() == Xcode.MINUS_EXPR) {
      Xobject xl = getInnerPointer(x.left());
      Xobject xr = getInnerPointer(x.right());

      if(xl != null) {
        // unsupported
        return xl;
      } else {
        return xr;
      }
    }

    // unsupported
    return null;
  }

  Xobject setArrayOffset(Ident baseId, int offset, Xobject expr) {
    topdownXobjectIterator exprIter = new topdownXobjectIterator(expr);
    for (exprIter.init(); !exprIter.end(); exprIter.next()) {
      Xobject x = exprIter.getXobject();
      switch(x.Opcode()) {
        case VAR:
        case ARRAY_ADDR: {
          String varName = x.getName();
          // System.out.println("setArrayOffset() [varName = " + varName + " ]");
          if (!baseId.getName().equals(varName)) continue;

          Xobject replaceXobject = Xcons.binaryOp(Xcode.MINUS_EXPR, baseId.Ref(), Xcons.IntConstant(offset));

          if (expr == x) return replaceXobject;
          // System.out.println("setArrayOffset() [replaceXobject = " + replaceXobject.toString() + " ]");
          exprIter.setXobject(replaceXobject);
          break;
        }
      }
    }
    return expr;
  }

  Xobject assignArrayConstInt(Ident baseId, int cint, Xobject expr) {
    topdownXobjectIterator exprIter = new topdownXobjectIterator(expr);
    for (exprIter.init(); !exprIter.end(); exprIter.next()) {
      Xobject x = exprIter.getXobject();
      // System.out.println("setOffset() [x = " + x.toString() + "]");
      switch(x.Opcode()) {
        case ARRAY_ADDR:
        case VAR: {
          String varName = x.getName();
          // System.out.println("assignArrayConstInt() [varName = " + varName + " ]");
          if (!baseId.getName().equals(varName)) continue;

          Xobject replaceXobject = Xcons.IntConstant(cint);

          if (expr == x) return replaceXobject;
          // System.out.println("setOffset() [replaceXobject = " + replaceXobject.toString() + " ]");
          exprIter.setXobject(replaceXobject);
          break;
        }
      }
    }
    return expr;
  }

  Ident getKernelChannel(Xtype type, int src, int dst) {
    String channelPrefix = "_ACC_CHANNEL";
    String name = channelPrefix + originalFuncName + "_" + type.getXcodeCId() + "_" + src + "_" + dst;

    for(Ident id : kernelChannels) {
      if(id.getName().equals(name)) {
        return id;
      }
    }
    Ident newCh = Ident.Local(name, type);
    kernelChannels.add(newCh);
    ACCclDecompileWriter.channels.add(newCh);

    return newCh;
  }

  boolean isCalculatable(Xobject x) {
    if(x.isIntConstant()) {
      return true;
    }

    if(x instanceof Ident) {
      return false;
    }

    switch(x.Opcode()) {
      case PLUS_EXPR:
      case MINUS_EXPR:
      case MUL_EXPR:
      case DIV_EXPR:
        return isCalculatable(x.left()) && isCalculatable(x.right());
      case LIST:
        if(x.Nargs() == 1) {
          return isCalculatable(x.getArg(0));
        }
      default:
        return false;
    }
  }

  int calcXobject(Xobject x) {
    if(x.isIntConstant()) {
      return x.getInt();
    }

    switch(x.Opcode()) {
      case PLUS_EXPR:
        return calcXobject(x.left()) + calcXobject(x.right());
      case MINUS_EXPR:
        return calcXobject(x.left()) - calcXobject(x.right());
      case MUL_EXPR:
        return calcXobject(x.left()) * calcXobject(x.right());
      case DIV_EXPR:
        return calcXobject(x.left()) / calcXobject(x.right());
      case LIST:
        if(x.Nargs() == 1) {
          return calcXobject(x.getArg(0));
        }
      default:
        return 0;
    }
  }

  Xobject makeArrayRef(Ident id, Xobject offset, List<Ident> iterList) {
    Xobject temp;
    XobjList refList;
    Xobject x;
    ArrayList<Xobject> list = new ArrayList<Xobject>();
    Xobject[] dummy = new Xobject[1];
    Xobject[] xa;

    if(iterList.isEmpty()) {
      return id.Ref();
    }

    temp = Xcons.binaryOp(Xcode.PLUS_EXPR, iterList.get(iterList.size() - 1).Ref(), offset);

    for(Ident ii: iterList) {
      list.add(0, ii.Ref());
    }

    xa = list.toArray(dummy);
    xa[0] = temp;
    refList = Xcons.List(xa);

    x = Xcons.arrayRef(id.Type(), id.Ref(), refList);

    return x;
  }

  Xobject elementSet(Ident leftId, Ident rightId, Xobject offset, List<Ident> iterList) {
    return elementSet(leftId, rightId, offset, offset, iterList);
  }

  Xobject elementSet(Ident leftId, Ident rightId, Xobject offsetL, Xobject offsetR, List<Ident> iterList) {
    Xobject setL;
    Xobject setR;

    setL = makeArrayRef(leftId, offsetL, iterList);
    setR = makeArrayRef(rightId, offsetR, iterList);

    return Xcons.Set(setL, setR);
  }

  Ident findGlobalArray(String s) {
    for(Ident id : globalArrayList) {
      if(id.getName().equals(s)) {
        return id;
      }
    }

    return null;
  }

  //
  // reduction Manager
  //
  private class ReductionManager {
    Ident counterPtr = null;
    Ident tempPtr = null;
    final List<Reduction> reductionList = new ArrayList<Reduction>();
    Xobject totalElementSize = Xcons.IntConstant(0);
    final Map<Reduction, Xobject> offsetMap = new HashMap<Reduction, Xobject>();
    Ident isLastVar = null;

    ReductionManager() {
      counterPtr = Ident.Param(ACC_REDUCTION_CNT_VAR, Xtype.Pointer(Xtype.unsignedType));//Ident.Var("_ACC_GPU_RED_CNT", Xtype.unsignedType, Xtype.Pointer(Xtype.unsignedType), VarScope.GLOBAL);
      tempPtr = Ident.Param(ACC_REDUCTION_TMP_VAR, Xtype.voidPtrType);//Ident.Var("_ACC_GPU_RED_TMP", Xtype.voidPtrType, Xtype.Pointer(Xtype.voidPtrType), VarScope.GLOBAL);
      isLastVar = Ident.Local("_ACC_GPU_IS_LAST_BLOCK", Xtype.intType);
      isLastVar.setProp(ACCgpuDecompiler.GPU_STORAGE_SHARED, true);
    }

    // make functions definition for reduction exeuted after reduction
    public XobjectDef makeReductionKernelDef(String deviceKernelName) {
      BlockList reductionKernelBody = Bcons.emptyBody();

      XobjList deviceKernelParamIds = Xcons.IDList();
      Xobject blockIdx = Xcons.Symbol(Xcode.VAR, Xtype.intType, "_ACC_block_x_id");
      Ident numBlocksId = Ident.Param("_ACC_GPU_RED_NUM", Xtype.intType);
      int count = 0;
      Iterator<Reduction> blockRedIter = reductionManager.BlockReductionIterator();
      while (blockRedIter.hasNext()) {
        Reduction reduction = blockRedIter.next();
        if (! reduction.needsExternalReduction()) continue;

        Block blockReduction;
        blockReduction = reduction.makeReductionLocBlockFuncCall(tempPtr, offsetMap.get(reduction), numBlocksId);
        Block ifBlock = Bcons.IF(Xcons.binaryOp(Xcode.LOG_EQ_EXPR, blockIdx, Xcons.IntConstant(count)), blockReduction, null);
        reductionKernelBody.add(ifBlock);
        count++;
      }

      for (Xobject x : _outerIdList) {
        Ident id = (Ident) x;
        Reduction reduction = reductionManager.findReduction(id);
        if (reduction != null && reduction.needsExternalReduction()) {
          deviceKernelParamIds.add(makeParamId_new(id)); //getVarId();
        }
      }

      deviceKernelParamIds.add(tempPtr);
      deviceKernelParamIds.add(numBlocksId);

      // make pointer paramter type "__global" for OpnenCL
      for(Xobject x : deviceKernelParamIds){
        Ident id = (Ident) x;
        if(id.Type().isPointer()) id.Type().setIsGlobal(true);
      }

      Ident deviceKernelId = _decl.getEnvDevice().declGlobalIdent(deviceKernelName, Xtype.Function(Xtype.voidType));
      ((FunctionType) deviceKernelId.Type()).setFuncParamIdList(deviceKernelParamIds);
      return XobjectDef.Func(deviceKernelId, deviceKernelParamIds, null, Bcons.COMPOUND(reductionKernelBody).toXobject());
    }

    public XobjList getBlockReductionParamIds() {
      return Xcons.List(Xcode.ID_LIST, tempPtr, counterPtr);
    }

    public Block makeLocalVarInitFuncs() {
      BlockList body = Bcons.emptyBody();
      Iterator<Reduction> blockRedIter = reductionManager.BlockReductionIterator();
      while (blockRedIter.hasNext()) {
        Reduction reduction = blockRedIter.next();
        System.out.println("makeLocalVarInitFuncs() [reduction.var = " + reduction.var.toString() + "]");
        System.out.println("makeLocalVarInitFuncs() [reduction.locVarID = " + reduction.locVarId.toString() + "]");
        System.out.println("makeLocalVarInitFuncs() [reduction.localVarID = " + reduction.localVarId.toString() + "]");
        if(reduction.onlyKernelLast()){
          Xtype constParamType = reduction.varId.Type();
          System.out.println("makeLocalVarInitFuncs() [reduction.varId.Type() = " + constParamType.toString() + "]");
          Ident constParamId = Ident.Param(reduction.var.toString(),    constParamType);
          Ident localId = reduction.localVarId;

          Xobject initialize = Xcons.Set(localId.Ref(), constParamId.Ref());
          body.add(Bcons.Statement(initialize));

          // body.add(reduction.makeInitReductionVarFuncCall());

          System.out.println("makeLocalVarInitFuncs() [block = " + Bcons.Statement(initialize).toString() + "]");
          // System.out.println("makeLocalVarInitFuncs() [block = " + reduction.makeInitReductionVarFuncCall().toString() + "]");
        }
      }

      if (body.isSingle()) {
        return body.getHead();
      } else {
        return Bcons.COMPOUND(body);
      }
    }

    public XobjList getBlockReductionLocalIds() {
      XobjList blockLocalIds = Xcons.IDList();
      Iterator<Reduction> blockRedIter = reductionManager.BlockReductionIterator();
      while (blockRedIter.hasNext()) {
        Reduction reduction = blockRedIter.next();
        if(reduction.onlyKernelLast()){
          blockLocalIds.add(reduction.getLocalReductionVarId());
          blockLocalIds.add(reduction.getLocReductionVarId());
        }
      }
      return blockLocalIds;
    }

    // for ACC.CL_reduction
    public Block makeReduceAndFinalizeFuncs() {
      BlockList body = Bcons.emptyBody();
      Iterator<Reduction> blockRedIter = reductionManager.BlockReductionIterator();
      while (blockRedIter.hasNext()) {
        Reduction reduction = blockRedIter.next();
        if (reduction.needsExternalReduction()) {
          body.add(reduction.makeReductionLocUpdateFuncCall());
        }
      }
      return Bcons.COMPOUND(body);
    }

    public Block makeReduceSetFuncs_CL() {
      BlockList body = Bcons.emptyBody();

      Iterator<Reduction> blockRedIter = reductionManager.BlockReductionIterator();
      while (blockRedIter.hasNext()) {
        Reduction reduction = blockRedIter.next();
        if (reduction.needsExternalReduction()) {
          body.add(reduction.makeReductionLocSetFuncCall(tempPtr));
        }
      }
      return Bcons.COMPOUND(body);
    }

    Reduction addReduction(ACCvar var, EnumSet<ACCpragma> execMethodSet) {
      System.out.println("addRedction ...");
      Reduction reduction = new Reduction(var, execMethodSet);
      reductionList.add(reduction);

      System.out.println("addRedction neesExternalReduction ="+reduction.needsExternalReduction());

      if (!reduction.needsExternalReduction()) return reduction;

      //tmp setting
      offsetMap.put(reduction, totalElementSize);

      Xtype varType = var.getId().Type();
      Xobject elementSize;
      if (varType.isPointer()) {
        elementSize = Xcons.SizeOf(varType.getRef());
      } else {
        elementSize = Xcons.SizeOf(varType);
      }
      totalElementSize = Xcons.binaryOp(Xcode.PLUS_EXPR, totalElementSize, elementSize);
      return reduction;
    }

    Reduction findReduction(Ident id) {
      for (Reduction red : reductionList) {
        if (red.varId == id) {
          return red;
        }
      }
      return null;
    }

    Iterator<Reduction> BlockReductionIterator() {
      return new BlockReductionIterator(reductionList);
    }

    boolean hasUsingTmpReduction() {
      return !offsetMap.isEmpty();
    }

    class BlockReductionIterator implements Iterator<Reduction> {
      final Iterator<Reduction> reductionIterator;
      Reduction re;

      public BlockReductionIterator(List<Reduction> reductionList) {
        this.reductionIterator = reductionList.iterator();
      }

      @Override
      public boolean hasNext() {
        while (true) {
          if (reductionIterator.hasNext()) {
            re = reductionIterator.next();
            if (re.useBlock()) {
              return true;
            }
          } else {
            return false;
          }
        }
      }

      @Override
      public Reduction next() {
        return re;
      }

      @Override
      public void remove() {
        //do nothing
      }
    }
  } // end of Reduction Manager

  //
  // Reduction
  //
  class Reduction {
    final EnumSet<ACCpragma> execMethodSet;  //final ACCpragma execMethod;
    final Ident localVarId;
    final Ident locVarId;  // hold location (address) for reduction
    final Ident varId;
    // --Commented out by Inspection (2015/02/24 21:12):Ident launchFuncLocalId;
    final ACCvar var;

    //Ident tmpId;
    Reduction(ACCvar var, EnumSet<ACCpragma> execMethodSet) {
      this.var = var;
      this.varId = var.getId();
      this.execMethodSet = EnumSet.copyOf(execMethodSet); //execMethod;

      //generate local var id
      String reductionVarPrefix = ACC_REDUCTION_VAR_PREFIX;

      if (execMethodSet.contains(ACCpragma.GANG)) reductionVarPrefix += "b";
      if (execMethodSet.contains(ACCpragma.VECTOR)) reductionVarPrefix += "t";

      reductionVarPrefix += "_";

      localVarId = Ident.Local(reductionVarPrefix + varId.getName(), varId.Type());
      if (execMethodSet.contains(ACCpragma.GANG) && !execMethodSet.contains(ACCpragma.VECTOR)) { //execMethod == ACCpragma._BLOCK) {
        localVarId.setProp(ACCgpuDecompiler.GPU_STORAGE_SHARED, true);
      }

      Xtype varId_type = Xtype.Pointer(varId.Type());
      varId_type.setIsGlobal(true);
      locVarId = Ident.Local(ACC_REDUCTION_VAR_PREFIX+"loc_"+ varId.getName(),varId_type);
    }

    public Block makeSingleBlockReductionFuncCall(Ident tmpPtrId) {
      XobjList args = Xcons.List(varId.getAddr(), tmpPtrId.Ref(), Xcons.IntConstant(getReductionKindInt()));
      return ACCutil.createFuncCallBlock("_ACC_gpu_reduction_singleblock", args);
    }

    public Block makeSingleBlockReductionFuncCall() {
      return makeSingleBlockReductionFuncCall(localVarId);
    }

    public void rewrite(Block b) {
      BasicBlockExprIterator iter = new BasicBlockExprIterator(b);
      for (iter.init(); !iter.end(); iter.next()) {
        Xobject expr = iter.getExpr();
        topdownXobjectIterator exprIter = new topdownXobjectIterator(expr);
        for (exprIter.init(); !exprIter.end(); exprIter.next()) {
          Xobject x = exprIter.getXobject();
          switch (x.Opcode()) {
          case VAR: {
            String varName = x.getName();
            if (varName.equals(varId.getName())) {
              exprIter.setXobject(localVarId.Ref());
            }
          }
            break;
          case VAR_ADDR: {
            String varName = x.getName();
            if (varName.equals(varId.getName())) {
              exprIter.setXobject(localVarId.getAddr());
            }
          }
            break;
          }
        }
      }
    }

    public boolean useThread() {
      return execMethodSet.contains(ACCpragma.VECTOR); //execMethod != ACCpragma._BLOCK;
    }

    public Ident getLocalReductionVarId() {
      return localVarId;
    }

    public Ident getLocReductionVarId() {
      return locVarId;
    }

    // initalize reduction variable
    public Block makeInitReductionVarFuncCall(Ident id) {
      String funcName = "_ACC_gpu_init_reduction_var";

      if (!execMethodSet.contains(ACCpragma.VECTOR)) { //execMethod == ACCpragma._BLOCK) {
        funcName += "_single";
      }

      if(ACC.CL_no_generic_f){
        funcName += "_"+varId.Type()+"_"+getReductionKindString();
        return ACCutil.createFuncCallBlock(funcName, Xcons.List(id.getAddr()));
      }
      System.out.println("makeInitReductionVarFuncCall() [not ACC.CL_no_generic_f]");

      return ACCutil.createFuncCallBlock(funcName, Xcons.List(id.getAddr(), Xcons.IntConstant(getReductionKindInt())));
    }

    public Block makeInitReductionVarFuncCall() {
      return makeInitReductionVarFuncCall(localVarId);
    }

    // reduction on block
    public Block makeBlockReductionFuncCall(Ident tmpPtrId, Xobject tmpOffsetElementSize, Ident numBlocks) {
      XobjList args = Xcons.List(varId.Ref(), Xcons.IntConstant(getReductionKindInt()),
                                 tmpPtrId.Ref(), tmpOffsetElementSize);
      if (numBlocks != null) {
        args.add(numBlocks.Ref());
      }
      return ACCutil.createFuncCallBlock("_ACC_gpu_reduction_block", args);
    }

    // for CL_reduction
    // set shared location for reduction
    public Block makeReductionLocSetFuncCall(Ident tmpPtrId)
    {
      XobjList args = Xcons.List(locVarId.Ref(), varId.getAddr(), tmpPtrId.Ref());
      String mang_name = "_ACC_gpu_reduction_loc_set";
      mang_name += "_"+varId.Type()+"_"+getReductionKindString();
      return ACCutil.createFuncCallBlock(mang_name, args);
    }

    // reduction on shared location
    public Block makeReductionLocUpdateFuncCall(){
      XobjList args = Xcons.List(locVarId.Ref(), localVarId.Ref());
      String mang_name = "_ACC_gpu_reduction_loc_update";
      mang_name += "_"+varId.Type()+"_"+getReductionKindString();
      return ACCutil.createFuncCallBlock(mang_name, args);
    }

    // reduction value on tempoary area
    public Block makeReductionLocBlockFuncCall(Ident tmpPtrId, Xobject tmpOffsetElementSize,Ident numBlocks) {
      XobjList args = Xcons.List(varId.Ref(), tmpPtrId.Ref(), tmpOffsetElementSize);

      if (numBlocks != null) {
        args.add(numBlocks.Ref());
      } else args.add(Xcons.IntConstant(0));

      String mang_name = "_ACC_gpu_reduction_loc_block";
      mang_name += "_"+varId.Type()+"_"+getReductionKindString();
      return ACCutil.createFuncCallBlock(mang_name, args);
    }

    String makeExecString(EnumSet<ACCpragma> execSet){
      StringBuilder sb = new StringBuilder();
      if(execSet.contains(ACCpragma.GANG)){
        sb.append('b');  // for gang   -> blockIdx.x
        if(execSet.contains(ACCpragma.VECTOR)) {
          sb.append('t');  // for acc loop gang vector
        }
      } else if(execSet.contains(ACCpragma.WORKER)) {
        sb.append("ty"); // for worker -> threadIdx.y
      } else if(execSet.contains(ACCpragma.VECTOR)) {
        sb.append('t');  // for vector -> threadIdx.x
      } else
        ACC.fatal("failed at parallelaization clause (available: gang, worker, vector)");
      return sb.toString();
    }

    public Block makeInKernelReductionFuncCall_CUDA(Ident dstId){
      Xobject dstArg = null;

      EnumSet<ACCpragma> execSet = EnumSet.copyOf(execMethodSet);
      dstArg = dstId != null? dstId.getAddr() : varId.getAddr();
      if(needsExternalReduction()){
        execSet.remove(ACCpragma.GANG);
        if(dstId == null){
          ACC.fatal("dstId must be specified");
        }
        //dstArg = localVarId.getAddr();
      }else{
        //dstArg = varId.Type().isPointer()? varId.Ref() : varId.getAddr();
      }

      String funcName = "_ACC_gpu_reduction_" + makeExecString(execSet);
      XobjList args = Xcons.List(dstArg, Xcons.IntConstant(getReductionKindInt()), localVarId.Ref());

      return ACCutil.createFuncCallBlock(funcName, args);
    }

    public Block makeInKernelReductionFuncCall(Ident dstId){
      return makeInKernelReductionFuncCall_CUDA(dstId);
    }

    public Block makeThreadReductionFuncCall(Ident varId) {
      XobjList args = Xcons.List(varId.getAddr(), localVarId.Ref(), Xcons.IntConstant(getReductionKindInt()));
      return ACCutil.createFuncCallBlock("_ACC_gpu_reduction_thread", args);
    }

    public Block makeTempWriteFuncCall(Ident tmpPtrId, Xobject tmpOffsetElementSize) {
      return makeTempWriteFuncCall(localVarId, tmpPtrId, tmpOffsetElementSize);
    }

    public Block makeTempWriteFuncCall(Ident id, Ident tmpPtrId, Xobject tmpOffsetElementSize) {
      return ACCutil.createFuncCallBlock("_ACC_gpu_reduction_tmp", Xcons.List(id.Ref(), tmpPtrId.Ref(), tmpOffsetElementSize));
    }

    int getReductionKindInt() {
      ACCpragma pragma = var.getReductionOperator();
      if (!pragma.isReduction()) ACC.fatal(pragma.getName() + " is not reduction clause");
      switch (pragma) {
      case REDUCTION_PLUS:
        return 0;
      case REDUCTION_MUL:
        return 1;
      case REDUCTION_MAX:
        return 2;
      case REDUCTION_MIN:
        return 3;
      case REDUCTION_BITAND:
        return 4;
      case REDUCTION_BITOR:
        return 5;
      case REDUCTION_BITXOR:
        return 6;
      case REDUCTION_LOGAND:
        return 7;
      case REDUCTION_LOGOR:
        return 8;
      default:
        ACC.fatal("getReductionKindInt: unknown reduction kind");
        return -1;
      }
    }

    String getReductionKindString() {
      ACCpragma pragma = var.getReductionOperator();
      if (!pragma.isReduction()) ACC.fatal(pragma.getName() + " is not reduction clause");
      switch (pragma) {
      case REDUCTION_PLUS:
        return "PLUS";
      case REDUCTION_MUL:
        return "MUL";
      case REDUCTION_MAX:
        return "MAX";
      case REDUCTION_MIN:
        return "MIN";
      case REDUCTION_BITAND:
        return "BITAND";
      case REDUCTION_BITOR:
        return "BITOR";
      case REDUCTION_BITXOR:
        return "BITXOR";
      case REDUCTION_LOGAND:
        return "LOGAND";
      case REDUCTION_LOGOR:
        return "LOGOR";
      default:
        ACC.fatal("getReductionKindString: unknown reduction kind");
        return "???";
      }
    }

    public boolean useBlock() {
      return execMethodSet.contains(ACCpragma.GANG);
    }

    public boolean existsAtomicOperation(){
      ACCpragma op = var.getReductionOperator();
      if(ACC.debug_flag) System.out.println("existsAtomicOperation type="+var.getId().Type());
      if(var.getId().Type().isBasic()){
        switch (var.getId().Type().getBasicType()) {
        case BasicType.FLOAT:
        case BasicType.INT:
          return op != ACCpragma.REDUCTION_MUL;
        }
      }
      return false;
    }

    public boolean onlyKernelLast() {
      //      if(ACC.device == AccDevice.PEZYSC) return false;

      return execMethodSet.contains(ACCpragma.GANG);
    }

    public boolean needsExternalReduction(){
      //      if(ACC.device == AccDevice.PEZYSC) return false;

      return !existsAtomicOperation() && execMethodSet.contains(ACCpragma.GANG);
    }
  } // end of Reduction
}
