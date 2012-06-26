package com.meltmedia.jgroups.aws;

import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.HttpClientUtils;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.jgroups.PhysicalAddress;
import org.jgroups.ViewId;
import org.jgroups.protocols.Discovery;
import org.jgroups.stack.IpAddress;
import org.jgroups.util.Promise;
import org.jgroups.annotations.Property;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.Tag;

/**
 * 
 * References
 * http://docs.amazonwebservices.com/AWSEC2/latest/CommandLineReference/ApiReference-cmd-DescribeInstances.html
 * http://docs.amazonwebservices.com/AWSEC2/latest/UserGuide/AESDG-chapter-instancedata.html
 * @author ctrimble
 *
 */
public class AWS_PING
  extends Discovery
{
	private static String INSTANCE_METADATA_BASE_URI = "http://169.254.169.254/latest/meta-data/";
	private static String GET_INSTANCE_ID = INSTANCE_METADATA_BASE_URI+"instance-id";
	private static String GET_LOCAL_ADDR = INSTANCE_METADATA_BASE_URI+"local-ipv4s";
	
	@Property(description="The port to communicate on.")
	protected String port;
	@Property(description="The AWS Access Key for the account to search.")
	protected String access_key;
	@Property(description="The AWS Secret Key for the account to search.")
	protected String secret_key;
	@Property(description="The URL of the AWS endpoint to use.")
	protected String endpoint;
	@Property(description="A semicolon delimited list of filters to search on. (name1=value1,value2;name2=value1,value2)")
	protected String filters;
	@Property(description="A list of tags that identify this cluster.")
	protected String tag_names;
	
	private String instanceId;
	private String localAddress;
	private int portNumber;
	private Collection<Filter> awsFilters;
	private List<String> awsTagNames;
	private AmazonEC2 ec2;
	
	public void init() throws Exception {
		super.init();
		
		portNumber = Integer.parseInt(port);
		
		// get the instance id and private IP address.
		HttpClient client = null;
		try {
			client = new DefaultHttpClient();
			instanceId = getUrl(client, INSTANCE_METADATA_BASE_URI);
			localAddress = getUrl(client, INSTANCE_METADATA_BASE_URI);
		}
		finally {
			HttpClientUtils.closeQuietly(client);
		}
		
		if( filters != null )
    		awsFilters = parseFilters(filters);
		if( tag_names != null ) 
			awsTagNames = parseTagNames(tag_names);
		
		ec2 = new AmazonEC2Client(new BasicAWSCredentials(access_key, secret_key));
		
	}
	
    public void start() throws Exception {
        super.start();
    }

    public void stop() {
        super.stop();
    }
	
	@Override
	public Collection<PhysicalAddress> fetchClusterMembers(String cluster_name) {
		List<PhysicalAddress> result = new ArrayList<PhysicalAddress>();
		
		DescribeInstancesRequest request = new DescribeInstancesRequest();
		List<Filter> filters = new ArrayList<Filter>();
		
		// if there are aws tags defined, then look them up and create filters.
		if( awsTagNames != null ) {
			Collection<Tag> instanceTags = getInstanceTags(ec2, instanceId);
			for( Tag instanceTag : instanceTags ) {
				if( awsTagNames.contains(instanceTag.getKey())) {
					filters.add(new Filter().withName("tag:"+instanceTag.getKey()).withValues(instanceTag.getValue()));
				}
			}
			
		}
		
		// if there are aws filters defined, then add them to the list.
		if( awsFilters != null ) {
			filters.addAll(awsFilters);
		}
		
		// NOTE: the reservations group nodes together by when they were started.  We need to dig through all of the reservations.
		DescribeInstancesResult filterResult = ec2.describeInstances(new DescribeInstancesRequest().withFilters(filters));
		for( Reservation reservation : filterResult.getReservations() ) {
			for( Instance instance : reservation.getInstances() ) {
					try {
						result.add(new IpAddress(instance.getPrivateIpAddress(), portNumber));
					} catch (UnknownHostException e) {
						log.warn("Could not build IpAddress for "+instance.getImageId());
					}
			}
		}
		
		return result;
	}


	@Override
	public boolean isDynamic() {
		return true;
	}
	
	@Override
	public boolean sendDiscoveryRequestsInParallel() {
		return true;
	}
	
	/**
	 * Returns the unique name for this protocol AWS_PING.
	 */
	@Override
	public String getName() {
		return "AWS_PING";
	}
	
	static Collection<Filter> parseFilters( String filters ) {
		List<Filter> awsFilters = new ArrayList<Filter>();
		
		String[] filterArray = filters.split("\\w*;\\w*");
		for( String filterString : filterArray ) {
			// clean up the filter, moving on if it is empty.
			String trimmed = filterString.trim();
			if( trimmed.length() == 0 ) continue;
			
			// isolate the key and the values, failing if there is a problem.
			String[] keyValues = trimmed.split("\\w*=\\w*");
			if( keyValues.length != 2 || keyValues[0].length() == 0 || keyValues[1].length() == 0 )
				throw new RuntimeException("Could not process key value pair '"+filterString+"'");
			
			// create the filter and add it to the list.
			awsFilters.add(new Filter().withName(keyValues[0]).withValues(keyValues[1].split("\\w*,\\w")));
		}
		return awsFilters;
	}
	
	static List<String> parseTagNames( String tagNames ) {
		return Arrays.asList(tagNames.split("\\w,\\w"));
	}
	
	static String getUrl( HttpClient client, String uri )
	  throws Exception
	{
	    HttpGet getInstance = new HttpGet();
	    getInstance.setURI(new URI(uri));
	    HttpResponse response = client.execute(getInstance);
	    if( response.getStatusLine().getStatusCode() != HttpStatus.SC_OK ) {
	    	throw new Exception("Could not get instance ID.");
	    }
	    return EntityUtils.toString(response.getEntity());
	}

	public static Collection<Tag> getInstanceTags(AmazonEC2 ec2, String instanceId) {
	  DescribeInstancesRequest request = new DescribeInstancesRequest();
	  List<String> ids = new ArrayList<String>();
	  ids.add(instanceId);
	  request.setInstanceIds(ids);
	  DescribeInstancesResult response = ec2.describeInstances(request);
	  List<Tag> tags = new ArrayList<Tag>();
	  List<Reservation> reservations = response.getReservations();
	  for(Reservation res : reservations) {
	    List<Instance> insts = res.getInstances();
	    for(Instance inst : insts) {
	      List<Tag> instanceTags = inst.getTags();
	      if(instanceTags != null && instanceTags.size() > 0) {
	        tags.addAll(instanceTags);
	      }
	    }
	  }
	  return tags;
	}


}