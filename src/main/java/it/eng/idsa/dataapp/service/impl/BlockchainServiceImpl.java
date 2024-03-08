package it.eng.idsa.dataapp.service.impl;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.configurationprocessor.json.JSONException;
import org.springframework.boot.configurationprocessor.json.JSONObject;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.eng.idsa.dataapp.service.BlockchainService;

@Service
public class BlockchainServiceImpl implements BlockchainService {

    private static final Logger logger = LoggerFactory.getLogger(BlockchainServiceImpl.class);

    private RestTemplate restTemplate;

    @Autowired
    public BlockchainServiceImpl(RestTemplateBuilder builder) {
        this.restTemplate = builder.build();
    }

    @Value("${application.notarization.enabled}")
	private Boolean notarizationEnabled;
    
    @Value("${application.notarization.protocol}")
	private String notarizationProtocol;

	@Value("${application.notarization.host}")
	private String notarizationHost;

	@Value("${application.notarization.port}")
	private String notarizationPort;

    @Value("${application.notarization.path}")
	private String notarizationPath;

    @Value("${application.notarization.network}")
	private String notarizationNetwork;

    @Value("${application.notarization.owner}")
	private String notarizationOwner;

    @Value("${application.notarization.prefix.filter.list}")
	private List<String> notarizationPrefixList;

    @Override
    public String createSmartContract(String entityId) throws Exception {
        
        // extract entity id prefix to check with notarizationPrefixList filter
        String entityPrefix = entityId.substring(0, entityId.lastIndexOf(":")+1);
        
        String smartContractAddress = null; 
        if(notarizationEnabled == null || notarizationEnabled.equals(Boolean.FALSE)) {
            
            logger.info("Notarization is disabled");
        
        } 
        /* smart contract created only if notarization service is enabled and 
        notarizationPrefixList filter is empty (disabled) or the entityPrefix is one of element of notarizationPrefixList filter */
        else if (notarizationEnabled.equals(Boolean.TRUE) && (notarizationPrefixList.isEmpty() || notarizationPrefixList.contains(entityPrefix))) {
            
            logger.info("Notarization Service: creating Smart Conctract for entity id " + entityId);
            smartContractAddress = callNotarizationService(entityId);
            logger.info("Notarization Service: Smart Conctract for entity id created {}", smartContractAddress);
        
        }

        return smartContractAddress;
        
    }

    private String callNotarizationService(String entityId) throws NumberFormatException, URISyntaxException, JSONException, JsonMappingException, JsonProcessingException {

        var uri = new URI(notarizationProtocol, null, notarizationHost, Integer.valueOf(notarizationPort),
				notarizationPath, null, null);
        
        logger.info("Forwarding form POST request to {}", uri.toString());

		var headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);

		var requestEntity = new HttpEntity<>(createNotarizationPayload(entityId), headers);
		logger.info("ENG Notarization Service: requestEntity              {}", requestEntity);

		logger.info("ENG Notarization Service: calling restTemplate.exchange()... {}", uri);
		
        ResponseEntity<String> resp = restTemplate.postForEntity(uri, requestEntity, String.class);

		logger.info("ENG Notarization Service: resp.getStatusCode()      {}", resp.getStatusCode());

        logger.info("ENG Notarization Service: response notarization   {}", resp.getBody());

        ObjectMapper mapper = new ObjectMapper();
        String smartContractAddres = mapper.readTree(resp.getBody()).get("smarContractAddress").asText();

        logger.info("ENG Notarization Service: Smart Contract Address   {}", smartContractAddres);

		return smartContractAddres;

    }

    private String createNotarizationPayload(String entityId) throws JSONException {

        JSONObject notarizationPayload = new JSONObject();

        notarizationPayload.put("network", notarizationNetwork);
        notarizationPayload.put("owner", notarizationOwner);
        notarizationPayload.put("cid", entityId);
        return notarizationPayload.toString();

    }

}
