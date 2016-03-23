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
package boa.aggregators;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;

import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.FileOutputFormat;

/**
 * A Boa ML aggregator to train models.
 * 
 * @author ankuraga
 */
abstract class MLAggregator extends Aggregator {
	
	public MLAggregator(final String s) {
		super(s);
	}
	
	public void saveModel(Object model) {
		FSDataOutputStream out = null;
		FileSystem fileSystem = null;
		Path filePath = null;
		try {
			JobContext context = (JobContext) getContext();
			Configuration configuration = context.getConfiguration();
			int boaJobId = configuration.getInt("boa.hadoop.jobid", 0);
			JobConf job = new JobConf(configuration);
			Path outputPath = FileOutputFormat.getOutputPath(job);
			fileSystem = outputPath.getFileSystem(context.getConfiguration());

			fileSystem.mkdirs(new Path("/boa", new Path("" + boaJobId)));
			filePath = new Path("/boa", new Path("" + boaJobId, new Path(("" + getKey()).split("\\[")[0] + "ML.model")));

			if (fileSystem.exists(filePath))
				return;

			out = fileSystem.create(filePath);
			ByteArrayOutputStream byteOutStream = new ByteArrayOutputStream();
			ObjectOutputStream objectOut = new ObjectOutputStream(byteOutStream);
			objectOut.writeObject(model);
			objectOut.close();

			byte[] serializedObject= byteOutStream.toByteArray();
			out.write(serializedObject, 0, serializedObject.length);

			this.collect(filePath.toString());

		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			try { if (out != null) out.close(); } catch (final Exception e) { e.printStackTrace(); }
		}
	}

	public void getAttributes(String a, FastVector fv){

		boolean inNominal = false;
		String[] tokens = input.split(",");
		FastVector tmpFV = new FastVector();

		for(String token : tokens){
			if(token.substring(0,1).equals("\"")){
				fv.addElement(new Attribute("String" + token))
			}
			if(token.substring(0,1).equals("[") || inNominal){
				inNominal = true;
				list = Arrays.copyOf(list, list.length + 1);
				if(token.substring(token.length()-1).equals("]")){
					inNominal = false;
					tmpFV.addElement(new Attribute(token.substring(0,token.length()-1)))
					fv.addElement(tmpFV);
					tmpFV = new FastVector();
				}else{
					if(token.substring(0,1).equals("[")){
						tmpFV.addElement(new Attribute(token.substring(1)));
					}else{
						tmpFV.addElement(new Attribute(token)));
					}
				}
			}
			try{
				if(Integer.parseInt(token.substring(0,1)) >=0 && Integer.parseInt(token.substring(0,1)) <=9){
					fv.addElement(new Attribute("Numeric" + token));
				}
			}catch(Exception e){
				e.printStackTrace();
			}
		}
	}
	
	/** {@inheritDoc} */
	@Override
	public abstract void aggregate(final String data, final String metadata) throws NumberFormatException, IOException, InterruptedException;
	
}
