package org.sunbird.actors;

import akka.dispatch.Mapper;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.actor.core.BaseActor;
import org.sunbird.cache.impl.RedisCache;
import org.sunbird.common.ContentParams;
import org.sunbird.common.dto.Request;
import org.sunbird.common.dto.Response;
import org.sunbird.common.dto.ResponseHandler;
import org.sunbird.common.exception.ClientException;
import org.sunbird.graph.dac.model.Node;
import org.sunbird.graph.nodes.DataNode;
import org.sunbird.graph.utils.NodeUtil;
import org.sunbird.utils.RequestUtils;
import scala.concurrent.Future;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.sunbird.common.Platform;
import static java.util.stream.Collectors.toList;

public class ContentActor extends BaseActor {

    public Future<Response> onReceive(Request request) throws Throwable {
        String operation = request.getOperation();
        switch(operation) {
            case "createContent": return create(request);
            case "readContent": return read(request);
            case "updateContent": return update(request);
            default: return ERROR(operation);
        }
    }

    private Future<Response> create(Request request) throws Exception {
        populateDefaultersForCreation(request);
        RequestUtils.restrictProperties(request);
        return DataNode.create(request, getContext().dispatcher())
                .map(new Mapper<Node, Response>() {
                    @Override
                    public Response apply(Node node) {
                        Response response = ResponseHandler.OK();
                        response.put("node_id", node.getIdentifier());
                        response.put("identifier", node.getIdentifier());
                        response.put("versionKey", node.getMetadata().get("versionKey"));
                        return response;
                    }
                }, getContext().dispatcher());
    }

    private Future<Response> update(Request request) throws Exception {
        populateDefaultersForUpdation(request);
        if(StringUtils.isBlank((String)request.get("versionKey")))
            throw new ClientException("ERR_INVALID_REQUEST", "Please Provide Version Key!");
        RequestUtils.restrictProperties(request);
        return DataNode.update(request, getContext().dispatcher())
                .map(new Mapper<Node, Response>() {
                    @Override
                    public Response apply(Node node) {
                        Response response = ResponseHandler.OK();
                        String identifier = node.getIdentifier().replace(".img","");
                        response.put("node_id", identifier);
                        response.put("identifier", identifier);
                        response.put("versionKey", node.getMetadata().get("versionKey"));
                        return response;
                    }
                }, getContext().dispatcher());
    }

    private Future<Response> read(Request request) throws Exception {
        List<String> fields = Arrays.stream(((String) request.get("fields")).split(","))
                .filter(field -> StringUtils.isNotBlank(field) && !StringUtils.equalsIgnoreCase(field, "null")).collect(Collectors.toList());
        request.getRequest().put("fields", fields);
        return DataNode.read(request, getContext().dispatcher())
                .map(new Mapper<Node, Response>() {
                    @Override
                    public Response apply(Node node) {
                        // Added for backward compatibility in mobile
                        if(!StringUtils.isEmpty((String)request.getRequest().get("mode")) && !StringUtils.equals("edit", (String)request.getRequest().get("mode")))
                            updateContentTaggedProperty(node);
                        Map<String, Object> metadata = NodeUtil.serialize(node, fields, (String) request.getContext().get("schemaName"), (String)request.getContext().get("version"));
                        metadata.put("identifier", node.getIdentifier().replace(".img", ""));
                        Response response = ResponseHandler.OK();
                        response.put("content", metadata);
                        return response;
                    }
                }, getContext().dispatcher());
    }

    private static void populateDefaultersForCreation(Request request) {
        setDefaultsBasedOnMimeType(request, ContentParams.create.name());
        setDefaultLicense(request);
    }

    private static void populateDefaultersForUpdation(Request request){
        if(request.getRequest().containsKey(ContentParams.body.name()))
            request.put(ContentParams.artifactUrl.name(), null);
    }

    private static void setDefaultLicense(Request request) {
        if(StringUtils.isEmpty((String)request.getRequest().get("license"))){
            String cacheKey = "channel_" + (String) request.getRequest().get("channel") + "_license";
	        String defaultLicense = RedisCache.get(cacheKey, null, 0);
            if(StringUtils.isNotEmpty(defaultLicense))
                request.getRequest().put("license", defaultLicense);
            else
                System.out.println("Default License is not available for channel: " + (String)request.getRequest().get("channel"));
        }
    }

    private static void setDefaultsBasedOnMimeType(Request request, String operation) {

        String mimeType = (String) request.get(ContentParams.mimeType.name());
        if (StringUtils.isNotBlank(mimeType) && operation.equalsIgnoreCase(ContentParams.create.name())) {
            if (StringUtils.equalsIgnoreCase("application/vnd.ekstep.plugin-archive", mimeType)) {
                String code = (String) request.get(ContentParams.code.name());
                if (null == code || StringUtils.isBlank(code))
                    throw new ClientException("ERR_PLUGIN_CODE_REQUIRED", "Unique code is mandatory for plugins");
                request.put(ContentParams.identifier.name(), request.get(ContentParams.code.name()));
            } else {
                request.put(ContentParams.osId.name(), "org.ekstep.quiz.app");
            }

            if (mimeType.endsWith("archive") || mimeType.endsWith("vnd.ekstep.content-collection")
                    || mimeType.endsWith("epub"))
                request.put(ContentParams.contentEncoding.name(), ContentParams.gzip.name());
            else
                request.put(ContentParams.contentEncoding.name(), ContentParams.identity.name());

            if (mimeType.endsWith("youtube") || mimeType.endsWith("x-url"))
                request.put(ContentParams.contentDisposition.name(), ContentParams.online.name());
            else
                request.put(ContentParams.contentDisposition.name(), ContentParams.inline.name());
        }
    }

    /**
     *
     * @param node
     */
    private void updateContentTaggedProperty(Node node) {
        Boolean contentTaggingFlag = Platform.config.hasPath("content.tagging.backward_enable")?
                Platform.config.getBoolean("content.tagging.backward_enable"): false;
        if(contentTaggingFlag) {
            List <String> contentTaggedKeys = Platform.config.hasPath("content.tagging.property") ?
                    Arrays.asList(Platform.config.getString("content.tagging.property").split(",")):
                    new ArrayList<>(Arrays.asList("subject","medium"));
            contentTaggedKeys.forEach(contentTagKey -> {
                if(node.getMetadata().containsKey(contentTagKey)) {
                    List<String> prop = prepareList(node.getMetadata().get(contentTagKey));
                    node.getMetadata().put(contentTagKey, prop.get(0));
                }
            });
        }
    }

    /**
     *
     * @param obj
     * @return
     */
    private static List<String> prepareList(Object obj) {
        List<String> list = new ArrayList<String>();
        try {
            if (obj instanceof String) {
                list.add((String) obj);
            } else if (obj instanceof String[]) {
                list = Arrays.asList((String[]) obj);
            } else if (obj instanceof List){
                list.addAll((List<String>) obj);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (null != list) {
            list = list.stream().filter(x -> org.apache.commons.lang3.StringUtils.isNotBlank(x) && !org.apache.commons.lang3.StringUtils.equals(" ", x)).collect(toList());
        }
        return list;
    }
}
