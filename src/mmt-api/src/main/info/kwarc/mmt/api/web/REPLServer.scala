package info.kwarc.mmt.api.web

import info.kwarc.mmt.api._
import frontend._
import documents._
import modules._
import symbols._
import objects._
import parser._
import checking._
import utils._

/** stores the state of a content-inputing REPL session */
class REPLSession(val doc: Document, val id: String, interpreter: Interpreter) {
  private val path = doc.path
  override def toString = doc.toString
  private var currentScope: HasParentInfo = IsDoc(path)
  private val errorCont = ErrorThrower
  private var counter = 0

  /** parses a declaration in a specific point (default: current point) of the document associated with this session (also stores in in controller) */
  def parseStructure(s: String, scopeOpt: Option[HasParentInfo] = None): StructuralElement = {
    val buffer = ParsingStream.stringToReader(s)
    val scope = scopeOpt.getOrElse(currentScope)
    val ps = ParsingStream(path.uri, scope, NamespaceMap(doc.path), interpreter.format, buffer)
    val se = interpreter(ps)(errorCont)
    se match {
      case r: MRef => currentScope = IsMod(r.target, LocalName.empty)
      case m: DeclaredModule => currentScope = IsMod(m.path, LocalName.empty)
      case nm: NestedModule => currentScope = IsMod(nm.module.path, LocalName.empty)
      case _ =>
    }
    se
  }

  /** closes the current container element, e.g., a theory */
  def parseElementEnd {
    currentScope match {
      case IsMod(m,_) =>
        val newScope = if (m.name.length > 1) IsMod(m ^, LocalName.empty) else IsDoc(m ^^)
        currentScope = newScope
      case IsDoc(_) => throw GeneralError("no open module")
    }
  }

  /** like parseStructure but for objects; the object is stored it as the definiens of a [[Constant]] declaration */
  def parseObject(s: String, scopeOpt: Option[HasParentInfo] = None): Declaration = {
    val scope = scopeOpt.getOrElse(currentScope)
    val mpath = scope match {
      case IsMod(m, _) => m
      case _ => throw GeneralError("can only parse term inside a theory")
    }
    val sref = SourceRef(path.uri, SourceRegion.ofString(s))
    val context = Context(mpath)
    val pu = ParsingUnit(sref, context, s, NamespaceMap.empty)
    val cr = interpreter(pu)(errorCont)
    val term = cr.term
    val termS = interpreter.simplifier(term, context, true)
    val df = Some(termS)
    val tp = cr.solution.flatMap(_.getO(CheckingUnit.unknownType).flatMap(_.df))
    val name = LocalName("res" + counter)
    counter += 1
    Constant(OMMOD(mpath), name, Nil, tp, df, None)
  }
}

import ServerResponse._

class REPLServer extends ServerExtension("repl") {
  private var sessions: List[REPLSession] = Nil

  def apply(request: ServerRequest): ServerResponse = {
    lazy val id = request.headers.get("mmtsession").getOrElse {
      throw LocalError("no mmtsession header")
    }
    lazy val path = DPath(mmt.baseURI) / "jupyter" / id
    lazy val currentSessionOpt = sessions.find(_.id == id)
    val command = request.query
    command match {
      case "show" =>
        val sessionsP = sessions map {s =>
          controller.presenter.asString(s.doc)
        }
        TextResponse(sessionsP.mkString("\n\n"))
      case "start" =>
        if (currentSessionOpt.nonEmpty) {
          throw LocalError("session id already exists")
        }
        val doc = new Document(path, root=true)
        controller.add(doc)
        val format = "mmt"
        val interpreter = controller.extman.get(classOf[Interpreter], format).getOrElse {
          throw LocalError("no parser found")
        }
        val s = new REPLSession(doc, id, interpreter)
        sessions ::= s
        TextResponse("new session started with id " + id)
      case "quit" =>
        currentSessionOpt match {
          case None =>
            TextResponse("session did not exist")
          case Some(s) =>
            sessions = sessions.filterNot(_ == s)
            controller.delete(path)
            TextResponse("session terminated with id " + id)
        }
      case _ =>
        val currentSession = currentSessionOpt.getOrElse {
          throw LocalError("unknown session")
        }
        val input = request.body.asString.trim
        val firstPart = input.takeWhile(c => !c.isWhitespace)
        val rest = input.substring(firstPart.length)
        firstPart match {
          case "end" =>
            // special case for closing the current container element (module etc.)
            currentSession.parseElementEnd
            TextResponse("closed module")
          case "eval" =>
             val d = currentSession.parseObject(input)
             TextResponse("read declaration " + d.toString)
          case "content" | _ =>
            val se = currentSession.parseStructure(input)
            TextResponse("read declaration " + se.toString)
        }
    }
  }
}
