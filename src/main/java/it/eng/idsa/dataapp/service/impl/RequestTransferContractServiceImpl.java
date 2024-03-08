package it.eng.idsa.dataapp.service.impl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.http.entity.ContentType;
import org.apache.http.protocol.HTTP;
import org.json.simple.JSONValue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import it.eng.idsa.dataapp.configuration.ECCProperties;
import it.eng.idsa.dataapp.model.RequestContract;
import it.eng.idsa.dataapp.service.RequestTransferContractService;
import it.eng.idsa.dataapp.util.ConstantUtil;
import it.eng.idsa.dataapp.web.rest.ProxyController;

@Service
public class RequestTransferContractServiceImpl implements RequestTransferContractService {

    private Path dataLakeDirectory;

    private ECCProperties eccProperties;
    @Autowired
    private ProxyController proxyController;

    public RequestTransferContractServiceImpl(ECCProperties eccProperties,
            @Value("${application.dataLakeDirectory}") Path dataLakeDirectory) {
        this.eccProperties = eccProperties;
        this.dataLakeDirectory = dataLakeDirectory;
    }

    @Override
    public RequestContract RequestTransferContract(String forwardTo) throws Exception {
        var requestContract = new RequestContract();

        try {
            // ******** DescriptionRequestMessage *********
            var response = DescriptionRequestMessage(forwardTo);
            parseDescriptionRequestMessage(requestContract, response);
            // ******** DescriptionRequestMessage *********

            // ******** DescriptionRequestMessage With OfferedResource *********
            response = DescriptionRequestMessageWithOfferedResource(forwardTo, requestContract.OfferedResource);
            parseDescriptionRequestMessageWithOfferedResource(requestContract, response);
            // ******** DescriptionRequestMessage With OfferedResource *********

            // ******** ContractRequestMessage *********
            response = ContractRequestMessage(forwardTo, requestContract);
            parseContractRequestMessage(requestContract, response);
            // ******** ContractRequestMessage *********

            // ******** ContractAgreementMessage *********
            response = ContractAgreementMessage(forwardTo, requestContract);
            if (!response.equals("MessageProcessedNotificationMessage")) {
                throw new Exception("Error requesting Contract in ContractAgreementMessage");
            }
            // ******** ContractAgreementMessage *********

        } catch (

        Exception e) {
            throw new Exception("Error requesting Contract", e);
        }
        return requestContract;
    }

    /*
     * pm.test("Retrieve offered resource", () => {
     *    let jsonData = pm.response.json()
     *   arrayOfObject = jsonData["ids:resourceCatalog"][0]["ids:offeredResource"];
     *    var result = arrayOfObject.find(obj => {
     *     
     * return obj["ids:contractOffer"][0]["ids:permission"][0]["ids:target"]["@id"] 
     * === "http://w3id.org/engrd/connector/artifact/1"
     *    })
     *    pm.collectionVariables.set("offered_resource", result["@id"])
     * });
     */
    private void parseDescriptionRequestMessage(RequestContract requestContract, String json) throws Exception {
        var jsonObject = new Gson().fromJson(json, JsonElement.class).getAsJsonObject();

        var resourceCatalog = jsonObject.getAsJsonArray("ids:resourceCatalog").get(0).getAsJsonObject();
        var offeredResources = resourceCatalog.getAsJsonArray("ids:offeredResource");

        JsonObject offeredResourceJSON = null;
        for (int i = 0; i < offeredResources.size(); i++) {
            String target = offeredResources.get(i).getAsJsonObject().getAsJsonArray("ids:contractOffer")
                    .get(0).getAsJsonObject().getAsJsonArray("ids:permission")
                    .get(0).getAsJsonObject().get("ids:target").getAsJsonObject().get("@id").getAsString();
            if (target.equals("http://w3id.org/engrd/connector/artifact/1")) {
                offeredResourceJSON = offeredResources.get(i).getAsJsonObject();
                break;
            }
        }

        if (offeredResourceJSON != null) {
            requestContract.OfferedResource = offeredResourceJSON.get("@id").getAsString();
        } else {
            throw new Exception("OfferedResource not found!");
        }
    }

    /*
     * pm.test("Retrieve contract artifact", function () {
     * var jsonData = pm.response.json();
     * pm.collectionVariables.set("contract_artifact",
     * jsonData["ids:representation"][0]["ids:instance"][0]["@id"])
     * });
     * 
     * pm.test("Retrieve contract id", function () {
     * var jsonData = pm.response.json();
     * pm.collectionVariables.set("contract_id",
     * jsonData["ids:contractOffer"][0]["@id"])
     * });
     * 
     * pm.test("Retrieve contract permission", function () {
     * var jsonData = pm.response.json();
     * pm.collectionVariables.set("contract_permission",
     * JSON.stringify(jsonData["ids:contractOffer"][0]["ids:permission"][0]))
     * });
     * 
     * pm.test("Retrieve contract provider", function () {
     * var jsonData = pm.response.json();
     * pm.collectionVariables.set("contract_provider",
     * jsonData["ids:contractOffer"][0]["ids:provider"]["@id"])
     * });
     */
    private void parseDescriptionRequestMessageWithOfferedResource(RequestContract requestContract, String json)
            throws Exception {

        var jsonObject = new Gson().fromJson(json, JsonElement.class).getAsJsonObject();
        requestContract.ContractArtifact = jsonObject.getAsJsonArray("ids:representation").get(0).getAsJsonObject()
                .getAsJsonArray("ids:instance").get(0).getAsJsonObject().get("@id").getAsString();

        requestContract.ContractId = jsonObject.getAsJsonArray("ids:contractOffer").get(0).getAsJsonObject()
                .get("@id").getAsString();

        requestContract.ContractPermission = jsonObject.getAsJsonArray("ids:contractOffer").get(0).getAsJsonObject()
                .getAsJsonArray("ids:permission").get(0).toString();

        requestContract.ContractProvider = jsonObject.getAsJsonArray("ids:contractOffer").get(0).getAsJsonObject()
                .get("ids:provider").getAsJsonObject().get("@id").getAsString();
    }

    private HttpHeaders getHttpHeaders() {
        var headers = new HttpHeaders();
        headers.add("Authorization", eccProperties.getHeaderAuthorization());
        headers.add(HTTP.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString());
        return headers;
    }

    private String DescriptionRequestMessage(String forwardTo) throws Exception {

        var bodyProxy = Map.ofEntries(
                Map.entry(ConstantUtil.MULTIPART_KEY, ConstantUtil.MULTIPART_FORM),
                Map.entry(ConstantUtil.FORWARD_TO, forwardTo),
                Map.entry(ConstantUtil.MESSAGE_TYPE, ConstantUtil.DESCRIPTION_REQUEST_MESSAGE));

        var response = proxyController.proxyRequest(getHttpHeaders(),
                JSONValue.toJSONString(bodyProxy),
                HttpMethod.POST);
        return (String) response.getBody();
    }

    private String DescriptionRequestMessageWithOfferedResource(String forwardTo, String offeredResource)
            throws Exception {

        var bodyProxy = Map.ofEntries(
                Map.entry(ConstantUtil.MULTIPART_KEY, ConstantUtil.MULTIPART_FORM),
                Map.entry(ConstantUtil.FORWARD_TO, forwardTo),
                Map.entry(ConstantUtil.MESSAGE_TYPE, ConstantUtil.DESCRIPTION_REQUEST_MESSAGE),
                Map.entry(ConstantUtil.REQUESTED_ELEMENT, offeredResource));

        var response = proxyController.proxyRequest(getHttpHeaders(),
                JSONValue.toJSONString(bodyProxy),
                HttpMethod.POST);
        return (String) response.getBody();
    }

    private String ContractRequestMessage(String forwardTo, RequestContract requestContract) throws Exception {

        var bytes = Files.readAllBytes(dataLakeDirectory.resolve("contract-request-message.json"));
        var bodyJSON = new Gson().fromJson(IOUtils.toString(bytes, "UTF8"), JsonElement.class)
                .getAsJsonObject();

        bodyJSON.addProperty(ConstantUtil.FORWARD_TO, forwardTo);
        bodyJSON.addProperty(ConstantUtil.MULTIPART_KEY, ConstantUtil.MULTIPART_FORM);
        bodyJSON.addProperty(ConstantUtil.REQUESTED_ELEMENT, requestContract.ContractArtifact);
        var payload = bodyJSON.get("payload").getAsJsonObject();
        payload.addProperty("@id", requestContract.ContractId);

        var contractPermissionJsonElement = new Gson().fromJson(requestContract.ContractPermission, JsonElement.class);

        payload.getAsJsonArray("ids:permission").add(contractPermissionJsonElement);

        payload.get("ids:provider").getAsJsonObject()
                .addProperty("@id", requestContract.ContractProvider);

        var response = proxyController.proxyRequest(getHttpHeaders(),
                bodyJSON.toString(),
                HttpMethod.POST);
        return (String) response.getBody();
    }

    /*
     * pm.test("Retrieve contract contract_agreement", function () {
     * pm.collectionVariables.set("contract_agreement",
     * JSON.stringify(pm.response.json()))
     * });
     * 
     * pm.test("Status code is 200", function () {
     * pm.response.to.have.status(200);
     * });
     * 
     * let responseData = pm.response.json();
     * pm.collectionVariables.set("transfer_contract", responseData["@id"]);
     */
    private void parseContractRequestMessage(RequestContract requestContract, String json) throws Exception {
        requestContract.ContractAgreement = json;
        var jsonObject = new Gson().fromJson(json, JsonElement.class).getAsJsonObject();
        requestContract.TransferContract = jsonObject.get("@id").getAsString();
    }

    private String ContractAgreementMessage(String forwardTo, RequestContract requestContract) throws Exception {

        var bodyProxy = Map.ofEntries(
                Map.entry(ConstantUtil.MULTIPART_KEY, ConstantUtil.MULTIPART_FORM),
                Map.entry(ConstantUtil.FORWARD_TO, forwardTo),
                Map.entry(ConstantUtil.MESSAGE_TYPE, ConstantUtil.CONTRACT_AGREEMENT_MESSAGE),
                Map.entry(ConstantUtil.REQUESTED_ARTIFACT, requestContract.ContractArtifact),
                Map.entry(ConstantUtil.PAYLOAD, requestContract.ContractAgreement));

        var response = proxyController.proxyRequest(getHttpHeaders(),
                JSONValue.toJSONString(bodyProxy),
                HttpMethod.POST);
        return (String) response.getBody();
    }

}
