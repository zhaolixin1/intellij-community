/*
 * User: anna
 * Date: 11-Mar-2008
 */
package com.jetbrains.python.codeInsight.imports;

import com.intellij.codeInsight.daemon.ReferenceImporter;
import com.intellij.codeInsight.daemon.impl.CollectHighlightsUtil;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.psi.*;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.python.PyElementTypes;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.codeInsight.PyCodeInsightSettings;
import com.jetbrains.python.codeInsight.controlflow.ControlFlowCache;
import com.jetbrains.python.codeInsight.controlflow.ScopeOwner;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.PyFileImpl;
import com.jetbrains.python.psi.impl.PyPsiUtils;
import com.jetbrains.python.psi.impl.PyQualifiedName;
import com.jetbrains.python.psi.resolve.CollectProcessor;
import com.jetbrains.python.psi.resolve.PyResolveUtil;
import com.jetbrains.python.psi.resolve.ResolveImportUtil;
import com.jetbrains.python.psi.search.PyProjectScopeBuilder;
import com.jetbrains.python.psi.stubs.PyClassNameIndex;
import com.jetbrains.python.psi.stubs.PyFunctionNameIndex;
import com.jetbrains.python.psi.stubs.PyVariableNameIndex;
import com.jetbrains.python.sdk.PythonSdkType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class PythonReferenceImporter implements ReferenceImporter {
  @Override
  public boolean autoImportReferenceAtCursor(@NotNull final Editor editor, @NotNull final PsiFile file) {
    if (!(file instanceof PyFile)) return false;
    int caretOffset = editor.getCaretModel().getOffset();
    Document document = editor.getDocument();
    int lineNumber = document.getLineNumber(caretOffset);
    int startOffset = document.getLineStartOffset(lineNumber);
    int endOffset = document.getLineEndOffset(lineNumber);

    List<PsiElement> elements = CollectHighlightsUtil.getElementsInRange(file, startOffset, endOffset);
    for (PsiElement element : elements) {
      if (element instanceof PyReferenceExpression && isImportable(element)) {
        final PyReferenceExpression refExpr = (PyReferenceExpression)element;
        if (refExpr.getQualifier() == null) {
          final PsiPolyVariantReference reference = refExpr.getReference();
          if (reference.resolve() == null) {
            AutoImportQuickFix fix = proposeImportFix(refExpr, reference);
            if (fix != null && fix.getCandidatesCount() == 1) {
              fix.invoke(file);
            }
            return true;
          }
        }
      }
    }
    return false;
  }

  @Override
  public boolean autoImportReferenceAt(@NotNull Editor editor, @NotNull PsiFile file, int offset) {
    if (!(file instanceof PyFile)) return false;
    PsiReference element = file.findReferenceAt(offset);
    if (element instanceof PyReferenceExpression && isImportable((PsiElement)element)) {
      final PyReferenceExpression refExpr = (PyReferenceExpression)element;
      if (refExpr.getQualifier() == null) {
        final PsiPolyVariantReference reference = refExpr.getReference();
        if (reference.resolve() == null) {
          AutoImportQuickFix fix = proposeImportFix(refExpr, reference);
          if (fix != null && fix.getCandidatesCount() == 1) {
            fix.invoke(file);
          }
          return true;
        }
      }
    }
    return false;
  }

  private static final TokenSet IS_IMPORT_STATEMENT = TokenSet.create(PyElementTypes.IMPORT_STATEMENT);

  @Nullable
  public static AutoImportQuickFix proposeImportFix(final PyElement node, PsiReference reference) {
    final String text = reference.getElement().getText();
    final String refText = reference.getRangeInElement().substring(text); // text of the part we're working with

    // don't propose meaningless auto imports if no interpreter is configured
    final Module module = ModuleUtil.findModuleForPsiElement(node);
    if (module != null && PythonSdkType.findPythonSdk(module) == null) {
      return null;
    }

    // don't show auto-import fix if we're trying to reference a variable which is defined below in the same scope
    ScopeOwner scopeOwner = PsiTreeUtil.getParentOfType(node, ScopeOwner.class);
    if (scopeOwner != null && ControlFlowCache.getScope(scopeOwner).containsDeclaration(refText)) {
      return null;
    }

    AutoImportQuickFix fix = new AutoImportQuickFix(node, reference, !PyCodeInsightSettings.getInstance().PREFER_FROM_IMPORT);
    Set<String> seen_file_names = new HashSet<String>(); // true import names
    // maybe the name is importable via some existing 'import foo' statement, and only needs a qualifier.
    // walk up collecting all such statements and analyzing
    CollectProcessor import_prc = new CollectProcessor(IS_IMPORT_STATEMENT);
    PyResolveUtil.treeCrawlUp(import_prc, node);
    List<PsiElement> result = import_prc.getResult();
    PsiFile existing_import_file = null; // if there's a matching existing import, this it the file it imports
    if (!result.isEmpty()) {
      for (PsiElement stmt : import_prc.getResult()) {
        for (PyImportElement ielt : ((PyImportStatement)stmt).getImportElements()) {
          final PyReferenceExpression src = ielt.getImportReferenceExpression();
          if (src != null) {
            PsiElement dst = src.getReference().resolve();
            if (dst instanceof PyFileImpl) {
              PyFileImpl dstFile = (PyFileImpl)dst;
              String name = ielt.getImportReferenceExpression().getReferencedName(); // ref is ok or matching would fail
              seen_file_names.add(name);
              PsiElement res = dstFile.findExportedName(refText);
              if (res != null && !(res instanceof PyFile) && !(res instanceof PyImportElement) && dstFile.equals(res.getContainingFile())) {
                existing_import_file = dstFile;
                fix.addImport(res, dstFile, ielt);
              }
            }
          }
        }
      }
    }

    // maybe some unimported file has it, too
    ProgressManager.checkCanceled(); // before expensive index searches
    // NOTE: current indices have limitations, only finding direct definitions of classes and functions.
    Project project = node.getProject();
    List<PsiElement> symbols = new ArrayList<PsiElement>();
    symbols.addAll(PyClassNameIndex.find(refText, project, true));
    GlobalSearchScope scope = PyProjectScopeBuilder.excludeSdkTestsScope(node);
    if (!isQualifier(node)) {
      symbols.addAll(PyFunctionNameIndex.find(refText, project, scope));
    }
    symbols.addAll(PyVariableNameIndex.find(refText, project, scope));
    if (isPossibleModuleReference(node)) {
      symbols.addAll(findImportableModules(node.getContainingFile(), refText, project, scope));
    }
    if (!symbols.isEmpty()) {
      for (PsiElement symbol : symbols) {
        if (isIndexableTopLevel(symbol)) { // we only want top-level symbols
          PsiFileSystemItem srcfile = symbol instanceof PsiFileSystemItem ? ((PsiFileSystemItem)symbol).getParent() : symbol.getContainingFile();
          if (srcfile != null && srcfile != existing_import_file && srcfile != node.getContainingFile() &&
              (ImportFromExistingAction.isRoot(srcfile) || PyNames.isIdentifier(FileUtil.getNameWithoutExtension(srcfile.getName()))) &&
               !isShadowedModule(srcfile)) {
            PyQualifiedName import_path = ResolveImportUtil.findCanonicalImportPath(srcfile, node);
            if (import_path != null && !seen_file_names.contains(import_path.toString())) {
              // a new, valid hit
              fix.addImport(symbol, srcfile, import_path, proposeAsName(node.getContainingFile(), refText, import_path));
              seen_file_names.add(import_path.toString()); // just in case, again
            }
          }
        }
      }
    }
    for(PyImportCandidateProvider provider: Extensions.getExtensions(PyImportCandidateProvider.EP_NAME)) {
      provider.addImportCandidates(reference, refText, fix);
    }
    if (fix.getCandidatesCount() > 0) {
      fix.sortCandidates();
      return fix;
    }
    return null;
  }

  private static boolean isShadowedModule(PsiFileSystemItem file) {
    if (file.isDirectory() || file.getName().equals(PyNames.INIT_DOT_PY)) {
      return false;
    }
    String name = FileUtil.getNameWithoutExtension(file.getName());
    final PsiDirectory directory = ((PsiFile)file).getContainingDirectory();
    if (directory == null) {
      return false;
    }
    PsiDirectory packageDir = directory.findSubdirectory(name);
    return packageDir != null && packageDir.findFile(PyNames.INIT_DOT_PY) != null;
  }

  private static boolean isQualifier(PyElement node) {
    return node.getParent() instanceof PyReferenceExpression && node == ((PyReferenceExpression)node.getParent()).getQualifier();
  }

  private static boolean isPossibleModuleReference(PyElement node) {
    if (node.getParent() instanceof PyCallExpression && node == ((PyCallExpression) node.getParent()).getCallee()) {
      return false;
    }
    if (node.getParent() instanceof PyArgumentList) {
      final PyArgumentList argumentList = (PyArgumentList)node.getParent();
      if (argumentList.getParent() instanceof PyClass) {
        final PyClass pyClass = (PyClass)argumentList.getParent();
        if (pyClass.getSuperClassExpressionList() == argumentList) {
          return false;
        }
      }
    }
    return true;
  }

  private static Collection<PsiElement> findImportableModules(PsiFile targetFile, String reftext, Project project, GlobalSearchScope scope) {
    List<PsiElement> result = new ArrayList<PsiElement>();
    PsiFile[] files = FilenameIndex.getFilesByName(project, reftext + ".py", scope);
    for (PsiFile file : files) {
      if (isImportableModule(targetFile, file)) {
        result.add(file);
      }
    }
    // perhaps the module is a directory, not a file
    PsiFile[] initFiles = FilenameIndex.getFilesByName(project, PyNames.INIT_DOT_PY, scope);
    for (PsiFile initFile : initFiles) {
      PsiDirectory parent = initFile.getParent();
      if (parent != null && parent.getName().equals(reftext)) {
        result.add(parent);
      }
    }
    return result;
  }

  public static boolean isImportableModule(PsiFile targetFile, PsiFileSystemItem file) {
    PsiDirectory parent = (PsiDirectory)file.getParent();
    return parent != null && file != targetFile &&
           (parent.findFile(PyNames.INIT_DOT_PY) != null ||
            ImportFromExistingAction.isRoot(parent) ||
            parent == targetFile.getParent());
  }

  private static boolean isIndexableTopLevel(PsiElement symbol) {
    if (symbol instanceof PsiFileSystemItem) {
      return true;
    }
    if (symbol instanceof PyClass || symbol instanceof PyFunction) {
      return PyPsiUtils.isTopLevel(symbol);
    }
    // only top-level target expressions are included in VariableNameIndex
    return symbol instanceof PyTargetExpression;
  }

  private static final String[] AS_PREFIXES = {"other_", "one_more_", "different_", "pseudo_", "true_"};

  // a no-frills recursive accumulating scan
  private static void collectIdentifiers(PsiElement node, Collection<String> dst) {
    PsiElement seeker = node.getFirstChild();
    while (seeker != null) {
      if (seeker instanceof NameDefiner) {
        for (PyElement named : ((NameDefiner)seeker).iterateNames()) {
          if (named != null) dst.add(named.getName());
        }
      }
      else collectIdentifiers(seeker, dst);
      seeker = seeker.getNextSibling();
    }
  }

  // find an unique name that does not clash with anything in the file, using ref_name and import_path as hints
  private static String proposeAsName(PsiFile file, String ref_name, PyQualifiedName import_path) {
    // a somehow brute-force approach: collect all identifiers wholesale and avoid clashes with any of them
    Set<String> ident_set = new HashSet<String>();
    collectIdentifiers(file, ident_set);
    // try the default, 'normal' name first; if it does not clash, propose no sustitute!
    if (! ident_set.contains(ref_name)) return null;
    // try flattened import path
    String path_name = import_path.join("_");
    if (! ident_set.contains(path_name)) return path_name;
    // ...with prefixes: a highly improbable situation already
    for (String prefix : AS_PREFIXES) {
      String variant = prefix + path_name;
      if (! ident_set.contains(variant)) return variant;
    }
    // if nothing helped, just bluntly add a number to the end. guaranteed to finish in ident_set.size()+1 iterations.
    int cnt = 1;
    while (cnt < Integer.MAX_VALUE) {
      String variant = path_name + Integer.toString(cnt);
      if (! ident_set.contains(variant)) return variant;
      cnt += 1;
    }
    return "SHOOSHPANCHICK"; // no, this cannot happen in a life-size file, just keeps inspections happy
  }

  public static boolean isImportable(PsiElement ref_element) {
    PyStatement parentStatement = PsiTreeUtil.getParentOfType(ref_element, PyStatement.class);
    if (parentStatement instanceof PyGlobalStatement || parentStatement instanceof PyNonlocalStatement ||
      parentStatement instanceof PyImportStatementBase) {
      return false;
    }
    return PsiTreeUtil.getParentOfType(ref_element, PyStringLiteralExpression.class, false, PyStatement.class) == null;
  }
}
