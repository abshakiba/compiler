/*
 * Copyright 2014, Hridesh Rajan, Robert Dyer, 
 *                 and Iowa State University of Science and Technology
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package boa.functions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;

import boa.types.Code.CodeRepository;
import boa.types.Code.Revision;
import boa.types.Diff.ChangedFile;
import boa.types.Toplevel.Project;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;

import weka.core.Attribute;
import weka.core.FastVector;
import weka.core.Instance;
import weka.core.Instances;
import weka.classifiers.Classifier;

/**
 * Boa domain-specific functions.
 * 
 * @author rdyer
 * @author ankuraga
 */
public class BoaIntrinsics {
	private final static String[] fixingRegex = {
		"\\bfix(s|es|ing|ed)?\\b",
		"\\b(error|bug|issue)(s)?\\b",
		//"\\b(bug|issue|fix)(s)?\\b\\s*(#)?\\s*[0-9]+",
		//"\\b(bug|issue|fix)\\b\\s*id(s)?\\s*(=)?\\s*[0-9]+"
	};

	private final static List<Matcher> fixingMatchers = new ArrayList<Matcher>();

	// FIXME Defining these variables as static to load model only once when it runs on multiple projects.  
	private static boolean loadFlag = false;
	private static Object unserializedObject = null;

	static {
		for (final String s : BoaIntrinsics.fixingRegex)
			fixingMatchers.add(Pattern.compile(s).matcher(""));
	}

	/**
	 * Is a Revision's log message indicating it is a fixing revision?
	 * 
	 * @param rev the revision to mine
	 * @return true if the revision's log indicates a fixing revision
	 */
	@FunctionSpec(name = "isfixingrevision", returnType = "bool", formalParameters = { "Revision" })
	public static boolean isfixingrevision(final Revision rev) {
		return isfixingrevision(rev.getLog());
	}

	/**
	 * Is a log message indicating it is a fixing revision?
	 * 
	 * @param log the revision's log message to mine
	 * @return true if the log indicates a fixing revision
	 */
	@FunctionSpec(name = "isfixingrevision", returnType = "bool", formalParameters = { "string" })
	public static boolean isfixingrevision(final String log) {
		final String lower = log.toLowerCase();
		for (final Matcher m : fixingMatchers)
			if (m.reset(lower).find())
				return true;

		return false;
	}

	/**
	 * Given the model URL, deserialize the model and return Model type
	 *
	 * @param Take URL for the model
	 * @return Model type after deserializing
	 */
	// TODO Take complete URL and then deserialize the model
	// FIXME Returning Object as a type, this needs to be changed once we defined Model Type
	@FunctionSpec(name = "load", returnType = "Model", formalParameters = {"string"})
	public static Object load(final String URL) throws Exception {
		try {
			if(loadFlag == false) {
				FSDataInputStream in = null;
				final Configuration conf = new Configuration();
				final FileSystem fileSystem = FileSystem.get(conf);
				final Path path = new Path("hdfs://boa-njt" + URL);

				if (in != null)
					try { in.close(); } catch (final Exception e) { e.printStackTrace(); }

				in = fileSystem.open(path);
				int numBytes = 0;
				final byte[] b = new byte[(int)fileSystem.getLength(path) + 1];
				long length = 0;

				in.read(b);

				ByteArrayInputStream bin = new ByteArrayInputStream(b);
				ObjectInputStream dataIn = new ObjectInputStream(bin);
				unserializedObject = dataIn.readObject();
				dataIn.close();
				loadFlag = true;
			}
		} catch(Exception ex){
		}
		return unserializedObject;
	}

	/**
	 * Classify instances for given ML model
	 *
	 * @param Take Model Type
	 * @return Predicted value for a instance
	 */
	@FunctionSpec(name = "classify", returnType = "float", formalParameters = { "Model","array of float"})
	public static double classify(final Object model, final double[] vector) throws Exception {
		List<Attribute> attribute = new ArrayList<Attribute>();
		int capacity = 1000000;
		int size = vector.length;
		int NumOfAttributes = size + 1;

		FastVector fvAttributes = new FastVector(NumOfAttributes);
		for(int i=0; i < NumOfAttributes; i++) {
				attribute.add(new Attribute("Attribute" + i));
				fvAttributes.addElement(attribute.get(i));
		}

		Instances testingSet = new Instances("Classifier", fvAttributes, capacity);
		testingSet.setClassIndex(NumOfAttributes-1);

		Instance instance = new Instance(NumOfAttributes);
		for(int i=0; i<size; i++) {
			 instance.setValue((Attribute)fvAttributes.elementAt(i), vector[i]);
		}

		Classifier classifier = (Classifier) model;
		double predval = classifier.classifyInstance(instance);

		return predval;
	}

	/**
	 * Does a Project contain a file of the specified type? This compares based on file extension.
	 * 
	 * @param p the Project to examine
	 * @param ext the file extension to look for
	 * @return true if the Project contains at least 1 file with the specified extension
	 */
	@FunctionSpec(name = "hasfiletype", returnType = "bool", formalParameters = { "Project", "string" })
	public static boolean hasfile(final Project p, final String ext) {
		for (int i = 0; i < p.getCodeRepositoriesCount(); i++)
			if (hasfile(p.getCodeRepositories(i), ext))
				return true;
		return false;
	}

	/**
	 * Does a CodeRepository contain a file of the specified type? This compares based on file extension.
	 * 
	 * @param cr the CodeRepository to examine
	 * @param ext the file extension to look for
	 * @return true if the CodeRepository contains at least 1 file with the specified extension
	 */
	@FunctionSpec(name = "hasfiletype", returnType = "bool", formalParameters = { "CodeRepository", "string" })
	public static boolean hasfile(final CodeRepository cr, final String ext) {
		for (int i = 0; i < cr.getRevisionsCount(); i++)
			if (hasfile(cr.getRevisions(i), ext))
				return true;
		return false;
	}

	/**
	 * Does a Revision contain a file of the specified type? This compares based on file extension.
	 * 
	 * @param rev the Revision to examine
	 * @param ext the file extension to look for
	 * @return true if the Revision contains at least 1 file with the specified extension
	 */
	@FunctionSpec(name = "hasfiletype", returnType = "bool", formalParameters = { "Revision", "string" })
	public static boolean hasfile(final Revision rev, final String ext) {
		for (int i = 0; i < rev.getFilesCount(); i++)
			if (rev.getFiles(i).getName().toLowerCase().endsWith("." + ext.toLowerCase()))
				return true;
		return false;
	}

	/**
	 * Matches a FileKind enum to the given string.
	 * 
	 * @param s the string to match against
	 * @param kind the FileKind to match
	 * @return true if the string matches the given kind
	 */
	@FunctionSpec(name = "iskind", returnType = "bool", formalParameters = { "string", "FileKind" })
	public static boolean iskind(final String s, final ChangedFile.FileKind kind) {
		return kind.name().startsWith(s);
	}

	public static <T> T stack_pop(final java.util.Stack<T> s) {
		if (s.empty())
			return null;
		return s.pop();
	}

	public static <T> T stack_peek(final java.util.Stack<T> s) {
		if (s.empty())
			return null;
		return s.peek();
	}

	public static String protolistToString(final List<String> l) {
		String s = "";
		for (final String str : l)
			if (s.isEmpty())
				s += str;
			else
				s += ", " + str;
		return s;
	}

	public static <T> String arrayToString(final T[] arr) {
		String s = "";
		for (final T val : arr)
			if (s.isEmpty())
				s += val;
			else
				s += ", " + val;
		return s;
	}

	public static String arrayToString(final long[] arr) {
		String s = "";
		for (final long val : arr)
			if (s.isEmpty())
				s += val;
			else
				s += ", " + val;
		return s;
	}

	public static String arrayToString(final double[] arr) {
		String s = "";
		for (final double val : arr)
			if (s.isEmpty())
				s += val;
			else
				s += ", " + val;
		return s;
	}

	public static String arrayToString(final boolean[] arr) {
		String s = "";
		for (final boolean val : arr)
			if (s.isEmpty())
				s += val;
			else
				s += ", " + val;
		return s;
	}

	public static <T> T[] basic_array(final T[] arr) {
		return arr;
	}

	public static <T> long[] basic_array(final Long[] arr) {
		long[] arr2 = new long[arr.length];
		for (int i = 0; i < arr.length; i++)
			arr2[i] = arr[i];
		return arr2;
	}

	public static <T> double[] basic_array(final Double[] arr) {
		double[] arr2 = new double[arr.length];
		for (int i = 0; i < arr.length; i++)
			arr2[i] = arr[i];
		return arr2;
	}

	public static <T> boolean[] basic_array(final Boolean[] arr) {
		boolean[] arr2 = new boolean[arr.length];
		for (int i = 0; i < arr.length; i++)
			arr2[i] = arr[i];
		return arr2;
	}

	public static <T> T[] concat(final T[] first, @SuppressWarnings("unchecked") final T[]... rest) {
		int totalLength = first.length;
		for (T[] array : rest)
			totalLength += array.length;
		
		final T[] result = Arrays.copyOf(first, totalLength);
		int offset = first.length;
		for (T[] array : rest) {
			System.arraycopy(array, 0, result, offset, array.length);
			offset += array.length;
		}
		return result;
	}

	public static long[] concat(final long[] first, final long[]... rest) {
		int totalLength = first.length;
		for (long[] array : rest)
			totalLength += array.length;
		
		final long[] result = new long[totalLength];
		System.arraycopy(first, 0, result, 0, first.length);

		int offset = first.length;
		for (long[] array : rest) {
			System.arraycopy(array, 0, result, offset, array.length);
			offset += array.length;
		}
		return result;
	}

	public static double[] concat(final double[] first, final double[]... rest) {
		int totalLength = first.length;
		for (double[] array : rest)
			totalLength += array.length;
		
		final double[] result = new double[totalLength];
		System.arraycopy(first, 0, result, 0, first.length);

		int offset = first.length;
		for (double[] array : rest) {
			System.arraycopy(array, 0, result, offset, array.length);
			offset += array.length;
		}
		return result;
	}

	public static boolean[] concat(final boolean[] first, final boolean[]... rest) {
		int totalLength = first.length;
		for (boolean[] array : rest)
			totalLength += array.length;
		
		final boolean[] result = new boolean[totalLength];
		System.arraycopy(first, 0, result, 0, first.length);

		int offset = first.length;
		for (boolean[] array : rest) {
			System.arraycopy(array, 0, result, offset, array.length);
			offset += array.length;
		}
		return result;
	}
}
