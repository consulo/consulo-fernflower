/**
 * @author VISTALL
 * @since 21/01/2023
 */
module org.jetbrains.fernflower
{
	requires consulo.execution.api;
	requires consulo.language.api;
	requires consulo.language.code.style.api;
	requires consulo.language.impl;
	requires consulo.logging.api;
	requires consulo.project.api;
	requires consulo.util.io;
	requires consulo.util.lang;
	requires consulo.virtual.file.system.api;

	requires asm;
	requires consulo.java.language.impl;
	requires fernflower;
}
