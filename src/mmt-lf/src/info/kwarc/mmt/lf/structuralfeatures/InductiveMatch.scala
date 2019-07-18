package info.kwarc.mmt.lf.structuralfeatures

import info.kwarc.mmt.api._
import objects._
import symbols._
import notations._
import checking._
import modules._
import frontend.Controller

import info.kwarc.mmt.lf._
import InternalDeclaration._
import InternalDeclarationUtil._
import TermConstructingFeatureUtil._
import inductiveUtil._
import StructuralFeatureUtils._
import StructuralFeatureUtil._


/** theories as a set of types of expressions */ 
class InductiveMatch extends StructuralFeature("match") with TypedParametricTheoryLike {
  
  /**
   * Checks the validity of the inductive type(s) to be constructed
   * @param dd the derived declaration from which the inductive type(s) are to be constructed
   */
  override def check(dd: DerivedDeclaration)(implicit env: ExtendedCheckingEnvironment) {}
  
  /**
   * Check that each definien matches the expected type
   */
  override def expectedType(dd: DerivedDeclaration, con: Controller, c: Constant): Option[Term] = {
    val (intDeclsPath, _, _) = ParamType.getParams(dd)
       
    con.library.getO(externalName(intDeclsPath, c.name)) match {case Some(c: Constant) => c.tp case _ => None}
  }

  /**
   * Elaborates an declaration of one or multiple mutual inductive types into their declaration, 
   * as well as the corresponding no confusion and no junk axioms
   * Constructs a structure whose models are exactly the (not necessarily initial) models of the declared inductive types
   * @param parent The parent module of the declared inductive types
   * @param dd the derived declaration to be elaborated
   */
  def elaborate(parent: ModuleOrLink, dd: DerivedDeclaration) = {
    val (indDefPath, context, indParams) = ParamType.getParams(dd)
    val (indD, indCtx) = controller.library.get(indDefPath) match {
      case indD: DerivedDeclaration if (indD.feature == "inductive") => (indD, Type.getParameters(indD))
      case d: DerivedDeclaration => throw LocalError("the referenced derived declaration is not of the feature inductive but of the feature "+d.feature+".")
      case _ => throw LocalError("Expected definition of corresponding inductively-defined types at "+indDefPath.toString()
            +" but no derived declaration found at that location.")
    }
    //check the indParams match the indCtx at least in length
    // TODO: Check the types match as well
    if (indCtx.length != indParams.length) throw LocalError("Incorrect length of parameters for the derived declaration "+indD.name+".\nExpected "+indCtx.length+" parameters but found "+indParams.length+".")
    
    implicit val parent = indD.path
    val intDecls = parseInternalDeclarations(indD, controller, Some(indCtx))
    var intTpls = tpls(intDecls)
    
    val indTplNames = intTpls map (_.name)
    
    var decls = parseInternalDeclarationsWithDefiniens(dd, controller, Some(context))
    
    val induct_paths = intTpls.map(t=>t.path.copy(name=inductName(t.name)))
    val unapply_paths = intDecls.map(t=>t.path.copy(name=unapplierName(t.name)))
    def defined(d: InternalDeclaration) = {decls.map(_.name) contains d.name}
    def definition(d: InternalDeclaration): Term = {
      val decl = decls.find(_.name == d.name).getOrElse(throw LocalError("The declaration "+d.name+" must also be defined."))
      decl.df.getOrElse(throw LocalError("No definien for declaration: "+decl.name))
    }
    val types = intTpls map (_.path)
    val mapDefs = intDecls map {
      case d: TypeLevel if defined(d) =>  
        PiOrEmpty(context++indCtx++d.argContext(None)._1, definition(d))
      case constr: Constructor if (defined(constr)) => 
        val tpl = constr.getTpl(intTpls)
        val Arrow(_, ret) = definition(tpl)
        PiOrEmpty(context++indCtx++constr.argContext(None)._1, MAP(constr.externalRet(indD.path), ret, definition(constr)))
      case d: Constructor => PiOrEmpty(context++indCtx++d.argContext(None)._1, NONE(d.externalRet))
      case d => PiOrEmpty(context, d.toTerm)
    }
    
    val modelDf = decls map (_.df.get)
    val indTplsArgs = intTpls map(_.argContext(None)._1)
    
    val indTplsDef = indTplNames map (nm => decls.find(_.name == nm).getOrElse(
        throw LocalError("No declaration found for the typelevel: "+nm)).df.get)
    val Tps = intTpls zip indTplsDef map {case (indTpl, indTplDef) => PiOrEmpty(context, Arrow(indTpl.toTerm, indTplDef))}
    val Dfs = indTplsArgs zip induct_paths map {case (indTplArgs, induct_path) => 
      LambdaOrEmpty(context++indTplArgs, ApplyGeneral(OMS(induct_path), context.map(_.toTerm)++modelDf++indTplArgs.map(_.toTerm)))}
    
    val inductDefs = (intTpls zip Tps zip Dfs) map {case ((tpl, tp), df) => 
      makeConst(dd.name/tpl.name, ()=> {tp}, false, () => {Some(df)})}
    //inductDefs foreach (d => log(defaultPresenter(d)(controller)))
    
    externalDeclarationsToElaboration(inductDefs, Some({c => log(defaultPresenter(c)(controller))}))
  }
  
  /**
   * checks whether d.tp matches the type decl.externalTp
   * @param d the declaration whoose type to check
   * @param decl the corresponding declaration which is to be defined by d
   * @note this will return an error if d.tp doesn't match decl.externalTp
   */
  def checkDecl(d: InternalDeclaration, decl: InternalDeclaration) {
    //TODO: This should be implemented to provide more accurate error messages
    //TODO: It should set the tp.analize fields of the internal declarations to the expected types (and definitions if any)
  }
}

object InductiveMatchRule extends StructuralFeatureRule(classOf[InductiveMatch], "match")