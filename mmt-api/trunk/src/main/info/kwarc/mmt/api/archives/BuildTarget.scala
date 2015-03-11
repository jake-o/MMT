package info.kwarc.mmt.api.archives

import info.kwarc.mmt.api._
import frontend._
import utils.File
import utils.FileConversion._

sealed abstract class BuildTargetModifier {
   def toString(dim: String) : String
}
case object Clean  extends BuildTargetModifier {
   def toString(dim: String) = "-" + dim
}
case class Update(ifChanged: Boolean, ifHadErrors: Boolean) extends BuildTargetModifier {
   def key = (if (ifChanged) "*" else "") + (if (ifHadErrors) "!" else "")
   def toString(dim: String) = dim + key
}
case object Build  extends BuildTargetModifier {
   def toString(dim: String) = dim
}

/** A BuildTarget provides build/update/clean methods that generate one or more dimensions in an [[Archive]]
 *  from an input dimension.
 */
abstract class BuildTarget extends Extension {
   /** a string identifying this build target, used for parsing commands, logging, error messages
    */
   def key: String
   
   /** defaults to the key */
   override def logPrefix = key

   /** number of required arguments, defaults to 0, override as needed */
   def requiredArguments(m: BuildTargetModifier): Int = 0

   /** build this target in a given archive */
   def build (a: Archive, args: List[String], in: List[String])
   /** update this target in a given archive */
   def update(a: Archive, args: List[String], up: Update, in: List[String])
   /** clean this target in a given archive */
   def clean (a: Archive, args: List[String], in: List[String])

   /** the main function to run the build target
    *  
    *  @param modifier chooses build, clean, or update
    *  @param arch the archive to build on
    *  @param in the folder inside the archive's inDim folder to which building in restricted (i.e., Nil for whole archive)
    *  @param additional arguments for the discretion of the BuildTarget; number must be equal to requiredArguments(modifier)
    */
   def apply(modifier: BuildTargetModifier, arch: Archive, in: List[String], args: List[String]) {
      val reqArgs = requiredArguments(modifier)
      if (reqArgs != args.length)
         throw ParseError("wrong nunmber of arguments, required: " + reqArgs)
      modifier match {
         case up: Update => update(arch, args, up, in)
         case Clean  => clean(arch, args, in)
         case Build  => build(arch, args, in)
      }
   }
   
   /** auxiliary method for deleting a file */
   protected def delete(f: File) {
       log("deleting " + f)
       f.delete
   }
}

/**
 * auxiliary type to represent the parameters and result of building a file/directory
 *  
 * @param inFile the input file
 * @param inPath the path of the input file inside the archive, relative to the input dimension
 * @param base the narration-base of the containing archive
 * @param outFile the intended output file
 * @param errorCont BuildTargets should report errors here 
 */
class BuildTask(val inFile: File, val isDir: Boolean, val inPath: List[String], val base: utils.URI,
                val outFile: File, val errorCont: ErrorHandler) {
   /** build targets should set this to true if they skipped the file so that it is not passed on to the parent directory */
   var skipped = false
   /** the MPath corresponding to the inFile if inFile is a file in a content-structured dimension */
   def contentMPath = Archive.ContentPathToMMTPath(inPath)
   /** the DPath corresponding to the inFile if inFile is a folder in a content-structured dimension */
   def contentDPath = Archive.ContentPathToDPath(inPath)
   /** the DPath corresponding to the inFile if inFile is in a narration-structured dimension */
   def narrationDPath = DPath(base / inPath)
   /** the name of the folder if inFile is a folder */
   def dirName: String = outFile.segments.init.last
}

/**
 * This trait provides common functionality for BuildTargets that traverse all files in the input dimension.
 * 
 * It implements BuildTarget in terms of a single abstract method called to build a path in the archive.
 */
abstract class TraversingBuildTarget extends BuildTarget {
  /** the input dimension/archive folder */
   def inDim:  ArchiveDimension 
  /** the output archive folder */
   def outDim: ArchiveDimension
   /** if true, multiple files/folders are built in parallel */
   def parallel: Boolean = false
     
   /** the file extension used for generated files, defaults to outDim, override as needed */
   def outExt: String = outDim match {
      case Dim(path@_*) => path.last
      case d => d.toString
   }
   /** the name that is used for the special file representing the containing folder, empty by default */
   protected val folderName = ""
   
   protected def getOutFile(a: Archive, inPath: List[String]) = (a / outDim / inPath).setExtension(outExt)
   protected def getFolderOutFile(a: Archive, inPath: List[String]) = a / outDim / inPath / (folderName + "." + outExt)
   protected def getErrorFile(a: Archive, inPath: List[String]) = (a / errors / key / inPath).setExtension("err")
   protected def getFolderErrorFile(a: Archive, inPath: List[String]) = a / errors / key / inPath / (folderName + ".err")
   
   /**
    * there is no inExt, instead we test to check which files should be used; 
    * this is often a test for the file extension
    * 
    * This must be such that all auxiliary files are skipped. 
    */
   def includeFile(name: String) : Boolean

   /** the main abstract method that implementations must provide: builds one file
     * @param a the containing archive  
     * @param bfFile information about input/output file etc
     */ 
   def buildFile(a: Archive, bf: BuildTask)

   /** similar to buildOne but called on every directory (after all its children have been processed)
     * @param a the containing archive  
     * @param bd information about input/output file etc
     * @param buildChildren results from building the children
     * This does nothing by default and can be overridden if needed.
     */ 
   def buildDir(a: Archive, bd: BuildTask, builtChildren: List[BuildTask]) {}
   
   /** entry point for recursive building */
   def build(a: Archive, args: List[String], in: List[String] = Nil) {
      buildAux(in)(a)
   }
   private def makeHandler(a: Archive, inPath: List[String], isDir : Boolean = false) = {
     val errFileName = if (isDir) getFolderErrorFile(a, inPath)
                             else getErrorFile(a, inPath)
     new ErrorWriter(errFileName, Some(report))
   }
   
   /** recursive building */
   private def buildAux(in : List[String] = Nil)(implicit a: Archive) {
       //build every file
       val prefix = "[" + inDim + " -> " + outDim + "] "
       a.traverse[BuildTask](inDim, in, includeFile, parallel) ({case Current(inFile,inPath) =>
           val outFile = getOutFile(a, inPath)
           log(prefix + inFile + " -> " + outFile)
           val errorCont = makeHandler(a, inPath)
           val bf = new BuildTask(inFile, false, inPath, a.narrationBase, outFile, errorCont)
           outFile.up.mkdirs
           buildFile(a, bf)
           errorCont.close
           controller.notifyListeners.onFileBuilt(a, this, inPath)
           // a.timestamps(this).set(inPath) not needed anymore
           bf
       }, {
          case (Current(inDir, inPath), builtChildren) =>
             val outFile = getFolderOutFile(a, inPath)
             val errorCont = makeHandler(a, inPath, true)
             val bd = new BuildTask(inDir, true, inPath, a.narrationBase, outFile, errorCont) 
             buildDir(a, bd, builtChildren)
             errorCont.close
            bd
       })
    }

   /** additional method that implementations may provide: cleans one file
     * @param a the containing archive  
     * @param curr the inDim whose output is to be deleted
     * deletes the output and error file by default, may be overridden to, e.g., delete auxiliary files
     */ 
   def cleanFile(a: Archive, curr: Current) {
      val outFile = getOutFile(a, curr.path)
      delete(outFile)
      delete(getErrorFile(a, curr.path))
      controller.notifyListeners.onFileBuilt(a, this, curr.path)
   }
   /** additional method that implementations may provide: cleans one directory
     * @param a the containing archive  
     * @param curr the outDim directory to be deleted
     * does nothing by default
     */ 
   def cleanDir(a: Archive, curr: Current) {}

   /** recursively delete output files */
   def clean (a: Archive, args: List[String], in: List[String] = Nil) {
       a.traverse[Unit](outDim, in, Archive.extensionIs(outExt), true)({c => cleanFile(a, c)}, {case (c,_) => cleanDir(a, c)})
   }
   
   /** @return status of input file, obtained by comparing to error file */
   private def modified(a: Archive, path: List[String]): (Modification, Boolean) = {
      val errorFile = getErrorFile(a, path)
      val inFile = a / inDim / path
      val mod = Modification(inFile, errorFile)
      val hadErrors = errorFile.toJava.length > 19 // TODO evil but more efficient than parsing the error file
      (mod, hadErrors)
   }
   
   /** recursively reruns build if the input file has changed
     *  
     * the decision is made based on the time stamps and the system's last-modified date
     */  
   def update(a: Archive, args: List[String], up: Update, in: List[String] = Nil) {
       a.traverse[Boolean](inDim, in, _ => true, parallel) ({case c @ Current(inFile, inPath) =>
          modified(a, inPath) match {
             case (Deleted,_) =>
                cleanFile(a, c)
                true
             case (Added, _) =>
                buildAux(inPath)(a)
                true
             case (Modified, hadErrors) =>
                if (up.ifChanged || (hadErrors && up.ifHadErrors)) {
                   cleanFile(a, c)
                   buildAux(inPath)(a)
                }
                false
             case (Unmodified, hadErrors) =>
                if (hadErrors && up.ifHadErrors) {
                   cleanFile(a, c)
                   buildAux(inPath)(a)
                }
                false
          }
       }, {case (c @ Current(inDir, inPath), childChanged) =>
          if (childChanged.exists(_ == true)) {
             val outFile = getFolderOutFile(a, inPath)
             val errorCont = makeHandler(a, inPath, true)
             val bd = new BuildTask(inDir, true, inPath, a.narrationBase, outFile, errorCont)
             errorCont.close
             buildDir(a, bd, Nil) // TODO pass proper builtChildren
             false
          } else
             false
       })
   }
}
