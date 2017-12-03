package org.ekstep.jobs.samza.service;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.samza.config.Config;
import org.apache.samza.system.OutgoingMessageEnvelope;
import org.apache.samza.system.SystemStream;
import org.apache.samza.task.MessageCollector;
import org.codehaus.jackson.map.ObjectMapper;
import org.ekstep.common.slugs.Slug;
import org.ekstep.common.util.AWSUploader;
import org.ekstep.common.util.S3PropertyReader;
import org.ekstep.content.common.ContentErrorMessageConstants;
import org.ekstep.content.enums.ContentErrorCodeConstants;
import org.ekstep.content.enums.ContentWorkflowPipelineParams;
import org.ekstep.content.pipeline.initializer.InitializePipeline;
import org.ekstep.content.publish.PublishManager;
import org.ekstep.content.util.PublishWebHookInvoker;
import org.ekstep.graph.service.common.DACConfigurationConstants;
import org.ekstep.jobs.samza.service.task.JobMetrics;
import org.ekstep.jobs.samza.util.JSONUtils;
import org.ekstep.jobs.samza.util.JobLogger;
import org.ekstep.jobs.samza.util.PublishPipelineParams;
import org.ekstep.learning.common.enums.ContentAPIParams;
import org.ekstep.learning.router.LearningRequestRouterPool;
import org.ekstep.learning.util.ControllerUtil;

import com.ilimi.common.Platform;
import com.ilimi.common.dto.NodeDTO;
import com.ilimi.common.dto.Response;
import com.ilimi.common.exception.ClientException;
import com.ilimi.graph.dac.enums.RelationTypes;
import com.ilimi.graph.dac.model.Node;
import com.ilimi.graph.dac.model.Relation;


public class PublishPipelineService implements ISamzaService {

	static JobLogger LOGGER = new JobLogger(PublishPipelineService.class);

	private String contentId;
	
	private static final int AWS_UPLOAD_RESULT_URL_INDEX = 1;

	private static final String s3Content = "s3.content.folder";

	private static final String s3Artifact = "s3.artifact.folder";
	
	private static final String COLLECTION_CONTENT_MIMETYPE = "application/vnd.ekstep.content-collection";

	private static ObjectMapper mapper = new ObjectMapper();

	private Map<String, Object> parameterMap = new HashMap<String,Object>();

	protected static final String DEFAULT_CONTENT_IMAGE_OBJECT_SUFFIX = ".img";

	private ControllerUtil util = new ControllerUtil();

	private Config config = null;
	
	private static String mid = "PPS."+System.currentTimeMillis()+"."+UUID.randomUUID();

	private static String startJobEventId = "BE_JOB_START";
	private static String endJobEventId = "BE_JOB_END";
	
	@Override
	public void initialize(Config config) throws Exception {
		this.config = config;
		JSONUtils.loadProperties(config);
		LOGGER.info("Service config initialized");
		LearningRequestRouterPool.init();
		LOGGER.info("Akka actors initialized");	
	}
	
	@Override
	@SuppressWarnings("unchecked")
	public void processMessage(Map<String, Object> message, JobMetrics metrics, MessageCollector collector) throws Exception {

		Map<String, Object> object = getInstruction(message);
		if (null == object || object.isEmpty()) {
			metrics.incSkippedCounter();
			return;
		}
		
		String nodeId = (String) object.get(PublishPipelineParams.id.name());
		try {
			Node node = getNode(nodeId);
			if (null != node) {
				LOGGER.info("Node fetched for publish and content enrichment operation : " + node.getIdentifier());
				Map<String, Object> edata = (Map<String, Object>) message.get(PublishPipelineParams.edata.name());
				int maxPublishRetry = 0;
				if(StringUtils.isNotEmpty((String)config.get("MAX_PUBLISH_RETRY")))
					maxPublishRetry = Integer.valueOf(maxPublishRetry);
				
				processJobStart(edata, object, message, node, collector);
				processJob(edata, object, message, node, collector, metrics, maxPublishRetry);
				processJobEnd(edata, object, message, collector, metrics, maxPublishRetry);
			}else {
				metrics.incSkippedCounter();
			}
		}catch(Exception e) {
			LOGGER.error("Failed to process message", message, e);
			metrics.incFailedCounter();
		}
	}
	
	@SuppressWarnings("unchecked")
	public Map<String, Object> getJobEvent(String jobEventId, Map<String, Object> object, Map<String, Object> message, String publishStatus){
		
		long unixTime = System.currentTimeMillis();
		Map<String, Object> startJobEvent = new HashMap<>();
		startJobEvent.put("eid", jobEventId);
		startJobEvent.put("ets", mid);
		startJobEvent.put("mid", unixTime);
	
		Map<String, Object> eks = new HashMap<>();
		eks.put("object", object);
		
		Map<String, Object> pedata = new HashMap<>((Map<String, Object>)message.get("edata"));
		pedata.put("peid", message.get("eid"));
		pedata.put("pets", message.get("ets"));
		pedata.put("pmid", message.get("mid"));
		pedata.put("status", publishStatus);
		eks.put("pedata", pedata);
		
		if(StringUtils.equalsIgnoreCase(startJobEventId, jobEventId)) {
			eks.put("message", "Started processing of publish samza job");
		}else if(StringUtils.equalsIgnoreCase(endJobEventId, jobEventId)){
			eks.put("message", "Publish job processing complete");
		}
		eks.put("class", (String)config.get("task.class"));
		eks.put("level", "INFO");
		startJobEvent.put("eks", eks);
		
		return startJobEvent;
	}
	private void processJobStart(Map<String, Object> edata, Map<String, Object> object, Map<String, Object> message, Node node, MessageCollector collector) throws Exception {
		if((Integer)edata.get("iteration") == 0 && StringUtils.equalsIgnoreCase((String)edata.get("status"), PublishPipelineParams.Pending.name())) {
			node.getMetadata().put("status", "Processing");
			util.updateNode(node);
			edata.put("status", PublishPipelineParams.Processing.name());
			message.put("edata", edata);
			pushEvent(getJobEvent(startJobEventId, object, message, PublishPipelineParams.Processing.name()), collector, Platform.config.getString("backend_telemetry_topic"));
		}
	}
	
	private void processJob(Map<String, Object> edata, Map<String, Object> object, Map<String, Object> message, Node node, 
			MessageCollector collector, JobMetrics metrics, int maxPublishRetry) throws Exception {
		if(((Integer)edata.get("iteration") == 0 && StringUtils.equalsIgnoreCase((String)edata.get("status"), PublishPipelineParams.Processing.name())) ||
				((Integer)edata.get("iteration") > 0 && 
						(Integer)edata.get("iteration") <maxPublishRetry && 
						StringUtils.equalsIgnoreCase((String)edata.get("status"), PublishPipelineParams.FAILED.name()))) {
			
			node.getMetadata().put("publish_type", edata.get("publish_type"));
			if(publishContent(node)) {
				metrics.incSuccessCounter();
				pushEvent(getJobEvent(endJobEventId, object, message, PublishPipelineParams.SUCCESS.name()), collector, Platform.config.getString("backend_telemetry_topic"));
			}else {
				edata.put("status", PublishPipelineParams.FAILED.name());
				edata.put("iteration", (int)edata.get("iteration")+1);
				message.put("edata", edata);
				pushEvent(message, collector, Platform.config.getString("failed_event_topic"));
			}
		}
	}
	private void processJobEnd(Map<String, Object> edata, Map<String, Object> object, Map<String, Object> message, 
			MessageCollector collector, JobMetrics metrics, int maxPublishRetry) throws Exception {
		if((Integer)edata.get("iteration") == maxPublishRetry && 
				StringUtils.equalsIgnoreCase((String)edata.get("status"), PublishPipelineParams.FAILED.name())) {
			metrics.incFailedCounter();
			pushEvent(getJobEvent(endJobEventId, object, message, PublishPipelineParams.FAILED.name()), collector, Platform.config.getString("backend_telemetry_topic"));
		}
	}
	
	private void pushEvent(Map<String, Object> message, MessageCollector collector, String topicId) throws Exception {
//		String jsonMessage = mapper.writeValueAsString(message);
		collector.send(new OutgoingMessageEnvelope(new SystemStream("kafka", topicId), message));
	}
	
	private Node getNode(String nodeId) {
		Node node = null;
		String imgNodeId = nodeId + DEFAULT_CONTENT_IMAGE_OBJECT_SUFFIX;
		node = util.getNode(PublishPipelineParams.domain.name(), imgNodeId);
		if (null == node) {
			node = util.getNode(PublishPipelineParams.domain.name(), nodeId);
		}
		return node;
	}
	
	private boolean publishContent(Node node) throws Exception{
		boolean published = true;
		LOGGER.debug("Publish processing start for content: " + node.getIdentifier());
		if (StringUtils.equalsIgnoreCase((String) node.getMetadata().get(PublishPipelineParams.mimeType.name()), COLLECTION_CONTENT_MIMETYPE)) {
			List<NodeDTO> nodes = util.getNodesForPublish(node);
			Stream<NodeDTO> nodesToPublish = filterAndSortNodes(nodes);
			nodesToPublish.forEach(nodeDTO -> publishCollectionNode(nodeDTO, (String)node.getMetadata().get("publish_type")));
			if (!nodes.isEmpty()) {
				node.getMetadata().put(ContentWorkflowPipelineParams.compatibilityLevel.name(), getCompatabilityLevel(nodes));
			}
		}
		publishNode(node, (String) node.getMetadata().get(PublishPipelineParams.mimeType.name()));
		LOGGER.debug("Publish processing done for content: " + node.getIdentifier());
		
		LOGGER.debug("Content Enrichment start for content: " + node.getIdentifier());
		String nodeId = node.getIdentifier().replace(".img", "");
		Node publishedNode = util.getNode(PublishPipelineParams.domain.name(), nodeId);
		if(!(StringUtils.equalsIgnoreCase((String)publishedNode.getMetadata().get(PublishPipelineParams.status.name()), PublishPipelineParams.Live.name())
				|| StringUtils.equalsIgnoreCase((String)publishedNode.getMetadata().get(PublishPipelineParams.status.name()), PublishPipelineParams.Unlisted.name()))) {
			return false;
		}
		if(StringUtils.equalsIgnoreCase(((String)publishedNode.getMetadata().get(PublishPipelineParams.mimeType.name())), COLLECTION_CONTENT_MIMETYPE)) {
			String versionKey = Platform.config.getString(DACConfigurationConstants.PASSPORT_KEY_BASE_PROPERTY);
			publishedNode.getMetadata().put(PublishPipelineParams.versionKey.name(), versionKey);
			processCollection(publishedNode);
			LOGGER.debug("Content Enrichment done for content: " + node.getIdentifier());
		}
		return published;
	}

	private Integer getCompatabilityLevel(List<NodeDTO> nodes) {
		final Comparator<NodeDTO> comp = (n1, n2) -> Integer.compare( n1.getCompatibilityLevel(), n2.getCompatibilityLevel());
		Optional<NodeDTO> maxNode = nodes.stream().max(comp);
		if (maxNode.isPresent())
			return maxNode.get().getCompatibilityLevel();
		else 
			return 1;
	}

	private List<NodeDTO> dedup(List<NodeDTO> nodes) {
		List<String> ids = new ArrayList<String>();
		List<String> addedIds = new ArrayList<String>();
		List<NodeDTO> list = new ArrayList<NodeDTO>();
		for (NodeDTO node : nodes) {
			if (isImageNode(node.getIdentifier()) && !ids.contains(node.getIdentifier())) {
				ids.add(node.getIdentifier());
			}
		}
		for (NodeDTO node : nodes) {
			if (!ids.contains(node.getIdentifier()) && !ids.contains(getImageNodeID(node.getIdentifier()))) {
				ids.add(node.getIdentifier());
			}
		}

		for (NodeDTO node : nodes) {
			if (ids.contains(node.getIdentifier()) && !addedIds.contains(node.getIdentifier())
					&& !addedIds.contains(getImageNodeID(node.getIdentifier()))) {
				list.add(node);
				addedIds.add(node.getIdentifier());
			}
		}
		return list;
	}

	private boolean isImageNode(String identifier) {
		return StringUtils.endsWithIgnoreCase(identifier, DEFAULT_CONTENT_IMAGE_OBJECT_SUFFIX);
	}

	private String getImageNodeID(String identifier) {
		return identifier + DEFAULT_CONTENT_IMAGE_OBJECT_SUFFIX;
	}

	private Stream<NodeDTO> filterAndSortNodes(List<NodeDTO> nodes) {
		return dedup(nodes).stream()
				.filter(node -> StringUtils.equalsIgnoreCase(node.getMimeType(),
						"application/vnd.ekstep.content-collection")
						|| StringUtils.equalsIgnoreCase(node.getStatus(), "Draft"))
				.filter(node -> StringUtils.equalsIgnoreCase(node.getVisibility(), "parent"))
				.sorted(new Comparator<NodeDTO>() {
					@Override
					public int compare(NodeDTO o1, NodeDTO o2) {
						return o2.getDepth().compareTo(o1.getDepth());
					}
				});
	}

	private void publishCollectionNode(NodeDTO node, String publishType) {
		Node graphNode = util.getNode("domain", node.getIdentifier());
		if(StringUtils.isNotEmpty(publishType)) {
			graphNode.getMetadata().put("publish_type", publishType);
		}
		publishNode(graphNode, node.getMimeType());
	}

	private void publishNode(Node node, String mimeType) {
		if (null == node)
			throw new ClientException(ContentErrorCodeConstants.INVALID_CONTENT.name(), ContentErrorMessageConstants.INVALID_CONTENT
					+ " | ['null' or Invalid Content Node (Object). Async Publish Operation Failed.]");
		String nodeId = node.getIdentifier().replace(".img", "");
		LOGGER.info("Publish processing start for node: " + nodeId);
		String basePath = PublishManager.getBasePath(nodeId, this.config.get("lp.tempfile.location"));
		LOGGER.info("Base path to store files: " + basePath);
		try {
			setContentBody(node, mimeType);
			LOGGER.debug("Fetched body from cassandra");
			parameterMap.put(PublishPipelineParams.node.name(), node);
			parameterMap.put(PublishPipelineParams.ecmlType.name(),
					PublishManager.isECMLContent(mimeType));
			LOGGER.info("Initializing the publish pipeline for: " + node.getIdentifier());
			InitializePipeline pipeline = new InitializePipeline(basePath, nodeId);
			pipeline.init(PublishPipelineParams.publish.name(), parameterMap);
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.info("Something Went Wrong While Performing 'Content Publish' Operation in Async Mode. | [Content Id: "
							+ nodeId + "]", e.getMessage());
			node.getMetadata().put(PublishPipelineParams.publishError.name(), e.getMessage());
			node.getMetadata().put(PublishPipelineParams.status.name(),
					PublishPipelineParams.Failed.name());
			util.updateNode(node);
			PublishWebHookInvoker.invokePublishWebKook(contentId, ContentWorkflowPipelineParams.Failed.name(),
					e.getMessage());
		} finally {
			try {
				FileUtils.deleteDirectory(new File(basePath.replace(nodeId, "")));
			} catch (Exception e2) {
				LOGGER.error("Error while deleting base Path: " + basePath, e2);
				e2.printStackTrace();
			}
		}
	}

	private void setContentBody(Node node, String mimeType) {
		if (PublishManager.isECMLContent(mimeType)) {
			node.getMetadata().put(PublishPipelineParams.body.name(), PublishManager.getContentBody(node.getIdentifier()));
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> getInstruction(Map<String, Object> message) {
		String eid = (String) message.get(PublishPipelineParams.eid.name());
		if (null == eid || !StringUtils.equalsIgnoreCase(eid, PublishPipelineParams.BE_JOB_REQUEST.name()))
			return null;

		Map<String, Object> edata = (Map<String, Object>) message.get(PublishPipelineParams.edata.name());
		if(StringUtils.equalsIgnoreCase((String)edata.get("action"), PublishPipelineParams.publish.name())) {
			Map<String, Object> object = (Map<String, Object>) message.get(PublishPipelineParams.object.name());
			if (null == object) 
				return null;
			
			int maxPublishRetry = 0;
			if(StringUtils.isNotEmpty((String)config.get("MAX_PUBLISH_RETRY")))
				maxPublishRetry = Integer.valueOf(maxPublishRetry);
			if (!StringUtils.equalsIgnoreCase((String) object.get(PublishPipelineParams.contentType.name()), PublishPipelineParams.Asset.name())) {
				if(((Integer)edata.get("iteration") == 0 && 
						StringUtils.equalsIgnoreCase((String)edata.get("status"), PublishPipelineParams.Pending.name())) || 
						((Integer)edata.get("iteration") > 0 && 
								(Integer)edata.get("iteration") <maxPublishRetry && 
								StringUtils.equalsIgnoreCase((String)edata.get("status"), PublishPipelineParams.FAILED.name())))
					return object;
			}
		}
		return null;
	}
	
	@SuppressWarnings("unchecked")
	private void processCollection(Node node) throws Exception {

		String graphId = node.getGraphId();
		String contentId = node.getIdentifier();
		Map<String, Object> dataMap = null;
		dataMap = processChildren(node, graphId, dataMap);
		LOGGER.debug("Children nodes process for collection - " + contentId);
		if(null != dataMap){
			for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
				if ("concepts".equalsIgnoreCase(entry.getKey()) || "keywords".equalsIgnoreCase(entry.getKey())) {
					continue;
				} else if ("subject".equalsIgnoreCase(entry.getKey())) {
					Set<Object> subject = (HashSet<Object>) entry.getValue();
					if (null != subject.iterator().next()) {
						node.getMetadata().put(entry.getKey(), (String) subject.iterator().next());
					}
				} else if ("medium".equalsIgnoreCase(entry.getKey())) {
					Set<Object> medium = (HashSet<Object>) entry.getValue();
					if (null != medium.iterator().next()) {
						node.getMetadata().put(entry.getKey(), (String) medium.iterator().next());
					}
				} else {
					Set<String> valueSet = (HashSet<String>) entry.getValue();
					String[] value = valueSet.toArray(new String[valueSet.size()]);
					node.getMetadata().put(entry.getKey(), value);
				}
			}
			Set<String> keywords = (HashSet<String>) dataMap.get("keywords");
			if (null != keywords && !keywords.isEmpty()) {
				if (null != node.getMetadata().get("keywords")) {
					Object object = node.getMetadata().get("keywords");
					if (object instanceof String[]) {
						String[] stringArray = (String[]) node.getMetadata().get("keywords");
						keywords.addAll(Arrays.asList(stringArray));
					} else if (object instanceof String) {
						String keyword = (String) node.getMetadata().get("keywords");
						keywords.add(keyword);
					}
				}
				List<String> keywordsList = new ArrayList<>();
				keywordsList.addAll(keywords);
				node.getMetadata().put("keywords", keywordsList);
			}
		}
		
		if(StringUtils.equalsIgnoreCase((String)node.getMetadata().get(PublishPipelineParams.visibility.name()), PublishPipelineParams.Default.name())) {
			processCollectionForTOC(node);
		}
		
		util.updateNode(node);
		if(null != dataMap) {
			if(null != dataMap.get("concepts")){
				List<String> concepts = new ArrayList<>();
				concepts.addAll((Collection<? extends String>) dataMap.get("concepts"));
				if (!concepts.isEmpty()) {
					util.addOutRelations(graphId, contentId, concepts, RelationTypes.ASSOCIATED_TO.relationName());
				}
			}
		}
	}
	
	private Map<String, Object> processChildren(Node node, String graphId, Map<String, Object> dataMap) throws Exception {
		List<String> children;
		children = getChildren(node);
		if(!children.isEmpty()){
			dataMap = new HashMap<String, Object>();
			for (String child : children) {
				Node childNode = util.getNode(graphId, child);
				dataMap = mergeMap(dataMap, processChild(childNode));
				processChildren(childNode, graphId, dataMap);
			}
		}
		return dataMap;
	}
	
	private List<String> getChildren(Node node) throws Exception {
		List<String> children = new ArrayList<>();
		if(null != node.getOutRelations()){
			for (Relation rel : node.getOutRelations()) {
				if (PublishPipelineParams.content.name().equalsIgnoreCase(rel.getEndNodeObjectType())) {
					children.add(rel.getEndNodeId());
				}
			}
		}
		return children;
	}
	
	private Map<String, Object> processChild(Node node) throws Exception {

		Map<String, Object> result = new HashMap<>();
		Set<Object> language = new HashSet<Object>();
		Set<Object> concepts = new HashSet<Object>();
		Set<Object> domain = new HashSet<Object>();
		Set<Object> grade = new HashSet<Object>();
		Set<Object> age = new HashSet<Object>();
		Set<Object> medium = new HashSet<Object>();
		Set<Object> subject = new HashSet<Object>();
		Set<Object> genre = new HashSet<Object>();
		Set<Object> theme = new HashSet<Object>();
		Set<Object> keywords = new HashSet<Object>();
		if (null != node.getMetadata().get("language")) {
			String[] langData = (String[]) node.getMetadata().get("language");
			language = new HashSet<Object>(Arrays.asList(langData));
			result.put("language", language);
		}
		if (null != node.getMetadata().get(PublishPipelineParams.domain.name())) {
			String[] domainData = (String[]) node.getMetadata().get(PublishPipelineParams.domain.name());
			domain = new HashSet<Object>(Arrays.asList(domainData));
			result.put("domain", domain);
		}
		if (null != node.getMetadata().get(PublishPipelineParams.gradeLevel.name())) {
			String[] gradeData = (String[]) node.getMetadata().get(PublishPipelineParams.gradeLevel.name());
			grade = new HashSet<Object>(Arrays.asList(gradeData));
			result.put("gradeLevel", grade);
		}
		if (null != node.getMetadata().get(PublishPipelineParams.ageGroup.name())) {
			String[] ageData = (String[]) node.getMetadata().get(PublishPipelineParams.ageGroup.name());
			age = new HashSet<Object>(Arrays.asList(ageData));
			result.put(PublishPipelineParams.ageGroup.name(), age);
		}
		if (null != node.getMetadata().get(PublishPipelineParams.medium.name())) {
			String mediumData = (String) node.getMetadata().get(PublishPipelineParams.medium.name());
			medium = new HashSet<Object>(Arrays.asList(mediumData));
			result.put(PublishPipelineParams.medium.name(), medium);
		}
		if (null != node.getMetadata().get(PublishPipelineParams.subject.name())) {
			String subjectData = (String) node.getMetadata().get(PublishPipelineParams.subject.name());
			subject = new HashSet<Object>(Arrays.asList(subjectData));
			result.put(PublishPipelineParams.subject.name(), subject);
		}
		if (null != node.getMetadata().get(PublishPipelineParams.genre.name())) {
			String[] genreData = (String[]) node.getMetadata().get(PublishPipelineParams.genre.name());
			genre = new HashSet<Object>(Arrays.asList(genreData));
			result.put(PublishPipelineParams.genre.name(), genre);
		}
		if (null != node.getMetadata().get(PublishPipelineParams.theme.name())) {
			String[] themeData = (String[]) node.getMetadata().get(PublishPipelineParams.theme.name());
			theme = new HashSet<Object>(Arrays.asList(themeData));
			result.put(PublishPipelineParams.theme.name(), theme);
		}
		if (null != node.getMetadata().get(PublishPipelineParams.keywords.name())) {
			String[] keyData = (String[]) node.getMetadata().get(PublishPipelineParams.keywords.name());
			keywords = new HashSet<Object>(Arrays.asList(keyData));
			result.put(PublishPipelineParams.keywords.name(), keywords);
		}
		for (Relation rel : node.getOutRelations()) {
			if ("Concept".equalsIgnoreCase(rel.getEndNodeObjectType())) {
				LOGGER.info("EndNodeId as Concept ->" + rel.getEndNodeId());
				concepts.add(rel.getEndNodeId());
			}
		}
		if (null != concepts && !concepts.isEmpty()) {
			result.put(PublishPipelineParams.concepts.name(), concepts);
		}
		return result;
	}
	
	@SuppressWarnings("unchecked")
	private Map<String, Object> mergeMap(Map<String, Object> dataMap, Map<String, Object> childDataMap) throws Exception {
		if (dataMap.isEmpty()) {
			dataMap.putAll(childDataMap);
		} else {
			for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
				Set<Object> value = new HashSet<Object>();
				if (childDataMap.containsKey(entry.getKey())) {
					value.addAll((Collection<? extends Object>) childDataMap.get(entry.getKey()));
				}
				value.addAll((Collection<? extends Object>) entry.getValue());
				dataMap.replace(entry.getKey(), value);
			}
			if (!dataMap.keySet().containsAll(childDataMap.keySet())) {
				for (Map.Entry<String, Object> entry : childDataMap.entrySet()) {
					if (!dataMap.containsKey(entry.getKey())) {
						dataMap.put(entry.getKey(), entry.getValue());
					}
				}
			}
		}
		return dataMap;
	}
	
	@SuppressWarnings("unchecked")
	public void processCollectionForTOC(Node node) throws Exception {

		String contentId = node.getIdentifier();
		LOGGER.info("Processing Collection Content :" + contentId);
		Response response = util.getHirerachy(contentId);
		if (null != response && null != response.getResult()) {
			Map<String, Object> content = (Map<String, Object>) response.getResult().get("content");
			Map<String, Object> mimeTypeMap = new HashMap<>();
			Map<String, Object> contentTypeMap = new HashMap<>();
			int leafCount = 0;
			getTypeCount(content, "mimeType", mimeTypeMap);
			getTypeCount(content, "contentType", contentTypeMap);
			content.put(ContentAPIParams.mimeTypesCount.name(), mimeTypeMap);
			content.put(ContentAPIParams.contentTypesCount.name(), contentTypeMap);
			leafCount = getLeafNodeCount(content, leafCount);
			content.put(ContentAPIParams.leafNodesCount.name(), leafCount);
			LOGGER.info("Write hirerachy to JSON File :" + contentId);
			String data = mapper.writeValueAsString(content);
			File file = new File(getBasePath(contentId) + "TOC.json");
			try {
				FileUtils.writeStringToFile(file, data);
				if (file.exists()) {
					LOGGER.info("Upload File to S3 :" + file.getName());
					String[] uploadedFileUrl = AWSUploader.uploadFile(getAWSPath(contentId), file);
					if (null != uploadedFileUrl && uploadedFileUrl.length > 1) {
						String url = uploadedFileUrl[AWS_UPLOAD_RESULT_URL_INDEX];
						LOGGER.info("Update S3 url to node" + url);
						node.getMetadata().put(ContentAPIParams.toc_url.name(), url);
					}
					FileUtils.deleteDirectory(file.getParentFile());
					LOGGER.info("Deleting Uploaded files");
				}
			} catch (Exception e) {
				LOGGER.error("Error while uploading file ", e);
			}
			node.getMetadata().put(ContentAPIParams.mimeTypesCount.name(), mimeTypeMap);
			node.getMetadata().put(ContentAPIParams.contentTypesCount.name(), contentTypeMap);
			node.getMetadata().put(ContentAPIParams.leafNodesCount.name(), leafCount);
		}
	}
	
	@SuppressWarnings("unchecked")
	private void getTypeCount(Map<String, Object> data, String type, Map<String, Object> typeMap) {
		List<Object> children = (List<Object>) data.get("children");
		if (null != children && !children.isEmpty()) {
			for (Object child : children) {
				Map<String, Object> childMap = (Map<String, Object>) child;
				String typeValue = childMap.get(type).toString();
				if (typeMap.containsKey(typeValue)) {
					int count = (int) typeMap.get(typeValue);
					count++;
					typeMap.put(typeValue, count);
				} else {
					typeMap.put(typeValue, 1);
				}
				if (childMap.containsKey("children")) {
					getTypeCount(childMap, type, typeMap);
				}
			}
		}

	}
	
	@SuppressWarnings("unchecked")
	private Integer getLeafNodeCount(Map<String, Object> data, int leafCount) {
		List<Object> children = (List<Object>) data.get("children");
		if (null != children && !children.isEmpty()) {
			for (Object child : children) {
				Map<String, Object> childMap = (Map<String, Object>) child;
				int lc = 0;
				lc = getLeafNodeCount(childMap, lc);
				leafCount = leafCount + lc;
			}
		} else {
			if (!COLLECTION_CONTENT_MIMETYPE.equals(data.get(PublishPipelineParams.mimeType.name())))
				leafCount++;
		}
		return leafCount;
	}
	
	private String getBasePath(String contentId) {
		String path = "";
		if (!StringUtils.isBlank(contentId))
			path = this.config.get("lp.tempfile.location") + File.separator + System.currentTimeMillis() + ContentAPIParams._temp.name()
					+ File.separator + contentId;
		return path;
	}

	private String getAWSPath(String identifier) {
		String folderName = S3PropertyReader.getProperty(s3Content);
		if (!StringUtils.isBlank(folderName)) {
			folderName = folderName + File.separator + Slug.makeSlug(identifier, true) + File.separator
					+ S3PropertyReader.getProperty(s3Artifact);
		}
		return folderName;
	}
}