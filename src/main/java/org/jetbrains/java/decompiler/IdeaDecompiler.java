/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.java.decompiler;

import com.intellij.java.language.impl.JavaClassFileType;
import com.intellij.java.language.impl.JavaFileType;
import com.intellij.java.language.impl.psi.impl.compiled.ClsFileImpl;
import com.intellij.java.language.psi.compiled.ClassFileDecompiler;
import consulo.annotation.component.ExtensionImpl;
import consulo.execution.ui.console.LineNumbersMapping;
import consulo.fernflower.ExactMatchLineNumbersMapping;
import consulo.internal.org.objectweb.asm.ClassReader;
import consulo.internal.org.objectweb.asm.ClassVisitor;
import consulo.internal.org.objectweb.asm.Opcodes;
import consulo.language.codeStyle.CodeStyleSettings;
import consulo.language.codeStyle.CodeStyleSettingsManager;
import consulo.language.codeStyle.CommonCodeStyleSettings;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.project.ProjectManager;
import consulo.util.io.FileUtil;
import consulo.util.lang.StringUtil;
import consulo.util.lang.ref.Ref;
import consulo.virtualFileSystem.VirtualFile;
import jakarta.inject.Inject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler;
import org.jetbrains.java.decompiler.main.extern.IBytecodeProvider;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Manifest;

@ExtensionImpl(id = "fernflower")
public class IdeaDecompiler extends ClassFileDecompiler.Light
{
	private static final Logger LOG = Logger.getInstance(IdeaDecompiler.class);

	private static final String BANNER = "//\n" + "// Source code recreated from a .class file by Consulo\n" + "// (powered by Fernflower decompiler)\n" + "//\n\n";

	private final IFernflowerLogger myLogger = new IdeaLogger();
	private final HashMap<String, Object> myOptions = new HashMap<>();

	@Inject
	public IdeaDecompiler(ProjectManager projectManager)
	{
		myOptions.put(IFernflowerPreferences.HIDE_DEFAULT_CONSTRUCTOR, "0");
		myOptions.put(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, "1");
		myOptions.put(IFernflowerPreferences.REMOVE_SYNTHETIC, "1");
		myOptions.put(IFernflowerPreferences.REMOVE_BRIDGE, "1");
		myOptions.put(IFernflowerPreferences.LITERALS_AS_IS, "1");
		myOptions.put(IFernflowerPreferences.NEW_LINE_SEPARATOR, "1");
		myOptions.put(IFernflowerPreferences.BYTECODE_SOURCE_MAPPING, "1");

		Project project = projectManager.getDefaultProject();
		CodeStyleSettings settings = CodeStyleSettingsManager.getInstance(project).getCurrentSettings();
		CommonCodeStyleSettings.IndentOptions options = settings.getIndentOptions(JavaFileType.INSTANCE);
		myOptions.put(IFernflowerPreferences.INDENT_STRING, StringUtil.repeat(" ", options.INDENT_SIZE));
	}

	@Override
	public boolean accepts(@NotNull VirtualFile file)
	{
		return true;
	}

	@NotNull
	@Override
	public CharSequence getText(@NotNull VirtualFile file)
	{
		if(!canHandle(file))
		{
			return ClsFileImpl.decompile(file);
		}

		try
		{
			Map<String, VirtualFile> files = new HashMap<>();
			files.put(file.getPath(), file);
			String mask = file.getNameWithoutExtension() + "$";
			for(VirtualFile child : file.getParent().getChildren())
			{
				if(child.getNameWithoutExtension().startsWith(mask) && file.getFileType() == JavaClassFileType.INSTANCE)
				{
					files.put(child.getPath(), child);
				}
			}
			MyByteCodeProvider provider = new MyByteCodeProvider(files);
			MyResultSaver saver = new MyResultSaver();

			BaseDecompiler decompiler = new BaseDecompiler(provider, saver, myOptions, myLogger);
			for(String path : files.keySet())
			{
				decompiler.addSpace(new File(path), true);
			}
			decompiler.decompileContext();

			int[] mapping = saver.myMapping;
			if(mapping != null)
			{
				file.putUserData(LineNumbersMapping.LINE_NUMBERS_MAPPING_KEY, new ExactMatchLineNumbersMapping(mapping));
			}

			return BANNER + saver.myResult;
		}
		catch(Exception e)
		{
			LOG.error(file.getUrl(), e);
			return ClsFileImpl.decompile(file);
		}
	}

	private static boolean canHandle(VirtualFile file)
	{
		if("package-info.class".equals(file.getName()))
		{
			LOG.info("skipped: " + file.getUrl());
			return false;
		}

		final Ref<Boolean> isGroovy = Ref.create(false);
		try
		{
			new ClassReader(file.contentsToByteArray()).accept(new ClassVisitor(Opcodes.ASM5)
			{
				@Override
				public void visit(int version, int access, String name, String signature, String superName, String[] interfaces)
				{
					for(String anInterface : interfaces)
					{
						if("groovy/lang/GroovyObject".equals(anInterface))
						{
							isGroovy.set(true);
							break;
						}
					}
				}

				@Override
				public void visitSource(String source, String debug)
				{
					if(source != null && source.endsWith(".groovy"))
					{
						isGroovy.set(true);
					}
				}
			}, ClassReader.SKIP_CODE);
		}
		catch(IOException ignore)
		{
		}
		if(isGroovy.get())
		{
			LOG.info("skipped Groovy class: " + file.getUrl());
			return false;
		}

		return true;
	}

	private static class MyByteCodeProvider implements IBytecodeProvider
	{
		private final Map<String, VirtualFile> myFiles;

		private MyByteCodeProvider(@NotNull Map<String, VirtualFile> files)
		{
			myFiles = files;
		}

		@Override
		public byte[] getBytecode(String externalPath, String internalPath) throws IOException
		{
			String path = FileUtil.toSystemIndependentName(externalPath);
			VirtualFile file = myFiles.get(path);
			assert file != null : path;
			return file.contentsToByteArray(false);
		}
	}

	private static class MyResultSaver implements IResultSaver
	{
		private String myResult = "";
		private int[] myMapping;

		@Override
		public void copyFile(String source, String destPath, String destFileName)
		{
		}

		@Override
		public void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping)
		{
			if(myResult.isEmpty())
			{
				myResult = content;
				myMapping = mapping;
			}
		}

		@Override
		public void saveFolder(String path)
		{
		}

		@Override
		public void createArchive(String path, String archiveName, Manifest manifest)
		{
		}

		@Override
		public void saveDirEntry(String s, String s1, String s2)
		{

		}

		@Override
		public void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName, String content)
		{
		}

		@Override
		public void copyEntry(String source, String destPath, String archiveName, String entry)
		{
		}

		@Override
		public void closeArchive(String path, String archiveName)
		{
		}
	}
}
