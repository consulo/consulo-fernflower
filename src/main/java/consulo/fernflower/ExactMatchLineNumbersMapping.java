package consulo.fernflower;

import consulo.execution.ui.console.LineNumbersMapping;

/**
 * @author VISTALL
 * @since 30-Apr-17
 */
public class ExactMatchLineNumbersMapping implements LineNumbersMapping
{
	private int[] myMapping;

	public ExactMatchLineNumbersMapping(int[] mapping)
	{
		myMapping = mapping;
	}

	@Override
	public int bytecodeToSource(int line)
	{
		for(int i = 0; i < myMapping.length; i += 2)
		{
			if(myMapping[i] == line)
			{
				return myMapping[i + 1];
			}
		}
		return -1;
	}

	@Override
	public int sourceToBytecode(int line)
	{
		for(int i = 0; i < myMapping.length; i += 2)
		{
			if(myMapping[i + 1] == line)
			{
				return myMapping[i];
			}
		}
		return -1;
	}
}