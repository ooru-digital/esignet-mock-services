package io.mosip.esignet.mock.identitysystem.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.RSAKey;
import io.mosip.esignet.mock.identitysystem.dto.*;
import io.mosip.esignet.mock.identitysystem.entity.KycAuth;
import io.mosip.esignet.mock.identitysystem.exception.MockIdentityException;
import io.mosip.esignet.mock.identitysystem.repository.AuthRepository;
import io.mosip.esignet.mock.identitysystem.service.AuthenticationService;
import io.mosip.esignet.mock.identitysystem.service.IdentityService;
import io.mosip.esignet.mock.identitysystem.util.HelperUtil;
import io.mosip.kernel.core.util.StringUtils;
import io.mosip.kernel.signature.dto.JWTSignatureRequestDto;
import io.mosip.kernel.signature.dto.JWTSignatureResponseDto;
import io.mosip.kernel.signature.service.SignatureService;
import lombok.extern.slf4j.Slf4j;
import org.jose4j.jwe.ContentEncryptionAlgorithmIdentifiers;
import org.jose4j.jwe.JsonWebEncryption;
import org.jose4j.jwe.KeyManagementAlgorithmIdentifiers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static io.mosip.esignet.mock.identitysystem.util.Constants.APPLICATION_ID;
import static io.mosip.esignet.mock.identitysystem.util.HelperUtil.ALGO_SHA3_256;


@Service
@Slf4j
public class AuthenticationServiceImpl implements AuthenticationService {

    private static final String PSUT_FORMAT = "%s%s";
    private static final String OTP_VALUE = "111111";

    private final String FIELD_ID_KEY="id";

    @Autowired
    private AuthRepository authRepository;

    @Autowired
    private IdentityService identityService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SignatureService signatureService;

    @Value("${mosip.mock.ida.kyc.transaction-timeout-secs:60}")
    private int transactionTimeoutInSecs;

    @Value("${mosip.mock.ida.kyc.encrypt:false}")
    private boolean encryptKyc;

    @Value("${mosip.mock.ida.kyc.default-language:eng}")
    private String defaultLanguage;

    @Value("${mosip.esignet.mock.authenticator.ida.otp-channels}")
    private List<String> otpChannels;

    @Value("#{${mosip.esignet.authenticator.auth-factor.kba.field-details}}")
    private List<Map<String,String>> fieldDetailList;

    @Value("${mosip.esignet.authenticator.auth-factor.kba.field-language}")
    private String fieldLang;

    @Value("${mosip.esignet.authenticator.credissuer.verify-otp-url}")
    private String verifyOTPUrl;

    @Value("${mosip.esignet.authenticator.credissuer.send-otp-url}")
    private String sendOTPUrl;

    @Value("${mosip.esignet.authenticator.credissuer.bearer-token}")
    private String credIssuerBeaerToken;

    ArrayList<String> trnHash = new ArrayList<>();

    @Override
    public KycAuthResponseDto kycAuth(String relyingPartyId, String clientId, KycAuthRequestDto kycAuthRequestDto) throws MockIdentityException {
        //TODO validate relying party Id and client Id
        log.info("kycAuth>>>>>>>>>>>>");
        IdentityData identityData = identityService.getIdentity(kycAuthRequestDto.getIndividualId());
        if (identityData == null) {
            throw new MockIdentityException("invalid_individual_id");
        }
        boolean authStatus = false;
        if (kycAuthRequestDto.getOtp() != null) {
            //check if the trn is available and active
            if (StringUtils.isEmpty(kycAuthRequestDto.getTransactionId())) {
                log.error("Invalid transaction Id");
                throw new MockIdentityException("invalid_transaction_id");
            }
            //TODO: This OTP needs to be fetched from credissuer.
            String otp = kycAuthRequestDto.getOtp();
            boolean isCredissuerVerfied = verifyOTPInCredissuer(otp, kycAuthRequestDto.getIndividualId());
            var trn_hash = HelperUtil.generateB64EncodedHash(ALGO_SHA3_256,
                    String.format(kycAuthRequestDto.getTransactionId(), kycAuthRequestDto.getIndividualId(), otp));

            var isValid = trnHash.contains(trn_hash);
            if (isValid) {
                //TODO: Make a call to get the OTP for a Credential ID
                authStatus = kycAuthRequestDto.getOtp().equals(otp);
                if (authStatus)
                    trnHash.remove(trn_hash);
                else
                    throw new MockIdentityException("auth_failed");
            } else {
                throw new MockIdentityException("invalid_transaction");
            }
        }

        if (kycAuthRequestDto.getPin() != null) {
            authStatus = kycAuthRequestDto.getPin().equals(identityData.getPin());
            if (!authStatus)
                throw new MockIdentityException("auth_failed");
        }

        if (kycAuthRequestDto.getBiometrics() != null) {
            authStatus = true; //TODO
        }

        if(kycAuthRequestDto.getKba()!=null){
            authStatus=validateKnowledgeBasedAuth(kycAuthRequestDto,identityData);
        }

        if (!CollectionUtils.isEmpty(kycAuthRequestDto.getTokens())) {
            authStatus = !StringUtils.isEmpty(kycAuthRequestDto.getTokens().get(0));
            if (!authStatus)
                throw new MockIdentityException("auth_failed");
        }
        KycAuth kycAuth = saveKycAuthTransaction(kycAuthRequestDto.getTransactionId(), relyingPartyId,
                kycAuthRequestDto.getIndividualId());

        KycAuthResponseDto kycAuthResponseDto = new KycAuthResponseDto();
        kycAuthResponseDto.setAuthStatus(authStatus);
        kycAuthResponseDto.setKycToken(kycAuth.getKycToken());
        kycAuthResponseDto.setPartnerSpecificUserToken(kycAuth.getPartnerSpecificUserToken());
        return kycAuthResponseDto;
    }

    @Override
    public KycExchangeResponseDto kycExchange(String relyingPartyId, String clientId, KycExchangeRequestDto kycExchangeRequestDto) throws MockIdentityException {
        //TODO validate relying party Id and client Id
        log.info("kycExchange>>>>>>>>>>>>");
        log.info("kycExchangeRequestDto>>>>>>", kycExchangeRequestDto);
        Optional<KycAuth> result = authRepository.findByKycTokenAndValidityAndTransactionIdAndIndividualId
                (kycExchangeRequestDto.getKycToken(), Valid.ACTIVE, kycExchangeRequestDto.getTransactionId(),
                        kycExchangeRequestDto.getIndividualId());

        log.info("result>>>>>>", result);

        if (!result.isPresent())
            throw new MockIdentityException("mock-ida-006");

        LocalDateTime savedTime = result.get().getResponseTime();
        long seconds = savedTime.until(kycExchangeRequestDto.getRequestDateTime(), ChronoUnit.SECONDS);
        if (seconds < 0 || seconds > transactionTimeoutInSecs) {
            result.get().setValidity(Valid.EXPIRED);
            authRepository.save(result.get());
            throw new MockIdentityException("mock-ida-007");
        }

        try {
            Map<String, Object> kyc = buildKycDataBasedOnPolicy(kycExchangeRequestDto.getIndividualId(),
                    kycExchangeRequestDto.getAcceptedClaims(), kycExchangeRequestDto.getClaimLocales());
            kyc.put("sub", result.get().getPartnerSpecificUserToken());

            result.get().setValidity(Valid.PROCESSED);
            authRepository.save(result.get());

            String finalKyc = this.encryptKyc ? getJWE(relyingPartyId, signKyc(kyc)) : signKyc(kyc);
            KycExchangeResponseDto kycExchangeResponseDto = new KycExchangeResponseDto();
            kycExchangeResponseDto.setKyc(finalKyc);
            return kycExchangeResponseDto;
        } catch (Exception ex) {
            log.error("Failed to build kyc data", ex);
            throw new MockIdentityException("mock-ida-008");
        }
    }

    @Override
    public SendOtpResult sendOtp(String relyingPartyId, String clientId, SendOtpDto sendOtpDto) throws MockIdentityException {
        //TODO validate relying party Id and client Id
        log.info("sending the otp>>>>>>>>>>>>");
        IdentityData identityData = identityService.getIdentity(sendOtpDto.getIndividualId());
        if (identityData == null) {
            log.error("Provided individual Id not found {}", sendOtpDto.getIndividualId());
            throw new MockIdentityException("invalid_individual_id");
        }

        if (!sendOtpDto.getOtpChannels().stream().allMatch(this::isSupportedOtpChannel)) {
            log.error("Invalid Otp Channels");
            throw new MockIdentityException("invalid_otp_channel");
        }

        String maskedEmailId = null;
        String maskedMobile = null;
        for (String channel : sendOtpDto.getOtpChannels()) {
            if (channel.equalsIgnoreCase("email")) {
                maskedEmailId = HelperUtil.maskEmail(identityData.getEmail());
            }
            if (channel.equalsIgnoreCase("phone") || channel.equalsIgnoreCase("mobile")) {
                maskedMobile = HelperUtil.maskMobile(identityData.getPhone());
            }
        }

        if(org.springframework.util.StringUtils.isEmpty(maskedEmailId) &&
                org.springframework.util.StringUtils.isEmpty(maskedMobile)) {
            log.error("neither email id nor mobile number found for the given individualId");
            throw new MockIdentityException("no_email_mobile_found");
        }
        //TODO: OTP vaule needs to come from API
        //TODO: Make a call to get the OTP for a Credential ID
        var trn_token_hash = HelperUtil.generateB64EncodedHash(ALGO_SHA3_256,
                String.format(sendOtpDto.getTransactionId(), sendOtpDto.getIndividualId(), 
                fetchOTPFromCredissuer(sendOtpDto.getIndividualId())));

        trnHash.add(trn_token_hash);
        return new SendOtpResult(sendOtpDto.getTransactionId(), maskedEmailId, maskedMobile);
    }

    private String fetchOTPFromCredissuer(String individualID) {
        String otpValue;
        try {
            HttpHeaders headers = new HttpHeaders();
		    headers.set("Authorization", "Bearer " + credIssuerBeaerToken);
		    HttpEntity<String> entity = new HttpEntity<>(headers);
            RestTemplate restTemplate = new RestTemplate();
            String otpApiUrl = sendOTPUrl + individualID;
            ResponseEntity<String> response = restTemplate.exchange(otpApiUrl, HttpMethod.GET, entity, String.class);
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(response.getBody());
            otpValue = rootNode.path("otp").asText(); // Assuming the OTP is returned as plain text
            log.info("OTP value fetched from API: {}", otpValue);
            return otpValue;
        } catch (Exception e) {
            log.error("Error fetching OTP from external API", e);
            throw new MockIdentityException("otp_fetch_error");
        }
    }

    private boolean verifyOTPInCredissuer(String otp, String credentialID) {
        try {
            HttpHeaders headers = new HttpHeaders();
		    headers.set("Authorization", "Bearer " + credIssuerBeaerToken);
            
            RestTemplate restTemplate = new RestTemplate();
    
            
            // Create the request body
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("credential_id", credentialID);
            requestBody.put("otp", otp);
    
            // Convert to JSON
            HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(requestBody, headers);
    
            // Make the POST request
            ResponseEntity<String> response = restTemplate.exchange(verifyOTPUrl, HttpMethod.POST, requestEntity, String.class);
            //ResponseEntity<String> response = restTemplate.postForEntity(verifyOTPUrl, requestEntity, String.class);
    
            // Check response status and extract the status field from the JSON response
            if (response.getStatusCode() == HttpStatus.OK) {
                ObjectMapper objectMapper = new ObjectMapper();
                JsonNode rootNode = objectMapper.readTree(response.getBody());
                String status = rootNode.path("status").asText();
    
                // Log and return true if status is "success"
                if ("success".equals(status)) {
                    log.info("OTP verified successfully for credential ID: {}", credentialID);
                    return true;
                } else {
                    log.error("OTP verification failed for credential ID: {}", credentialID);
                    return false;
                }
            } else {
                log.error("Failed to verify OTP. HTTP Status: {}", response.getStatusCode());
                return false;
            }
        } catch (Exception e) {
            log.error("Error verifying OTP with external API", e);
            throw new MockIdentityException("otp_verification_error");
        }
    }
    

    private boolean validateKnowledgeBasedAuth(KycAuthRequestDto kycAuthRequestDto,IdentityData identityData){
        if(CollectionUtils.isEmpty(fieldDetailList) || StringUtils.isEmpty(fieldLang)){
            log.error("KBA field details not configured");
            throw new MockIdentityException("auth-failed");
        }
        String encodedChallenge=kycAuthRequestDto.getKba();
        try{
            byte[] decodedBytes = Base64.getUrlDecoder().decode(encodedChallenge);
            String challenge = new String(decodedBytes, StandardCharsets.UTF_8);
            Map<String, String> challengeMap = objectMapper.readValue(challenge, Map.class);

            for(Map<String,String> fieldDetail:fieldDetailList){
                if(challengeMap.containsKey(fieldDetail.get(FIELD_ID_KEY))) {
                    String challengeField = fieldDetail.get(FIELD_ID_KEY);
                    String challengeValue = challengeMap.get(challengeField);
                    if(challengeField.equals("dateOfBirth")) {
                        challengeValue = new SimpleDateFormat("yyyy/MM/dd").format(
                                new SimpleDateFormat(fieldDetail.get("format")).parse(challengeValue));
                    }
                    String identityDataValue = getIdentityDataFieldValue(identityData, challengeField);
                    if(!identityDataValue.equals(challengeValue)) {
                        return false;
                    }
                }
            }
        }catch (Exception e){
            log.error("Failed to decode KBA challenge or compare it with IdentityData", e);
            throw new MockIdentityException("auth-failed");
        }
        return true;
    }
    private String getIdentityDataFieldValue(IdentityData identityData,String challengeField) throws Exception {
        Field field = identityData.getClass().getDeclaredField(challengeField);
        field.setAccessible(true);
        Object fieldValue = field.get(identityData);
        if(fieldValue instanceof List){
            List<LanguageValue> languageValues = (List<LanguageValue>) fieldValue;
            for(LanguageValue languageValue:languageValues){
                if(languageValue.getLanguage().equals(fieldLang)){
                    return languageValue.getValue();
                }
            }
        }
        return (String) fieldValue;
    }

    private String signKyc(Map<String, Object> kyc) throws JsonProcessingException {
        String payload = objectMapper.writeValueAsString(kyc);
        JWTSignatureRequestDto jwtSignatureRequestDto = new JWTSignatureRequestDto();
        jwtSignatureRequestDto.setApplicationId(APPLICATION_ID);
        jwtSignatureRequestDto.setReferenceId("");
        jwtSignatureRequestDto.setIncludePayload(true);
        jwtSignatureRequestDto.setIncludeCertificate(false);
        jwtSignatureRequestDto.setDataToSign(HelperUtil.b64Encode(payload));
        jwtSignatureRequestDto.setIncludeCertHash(false);
        JWTSignatureResponseDto responseDto = signatureService.jwtSign(jwtSignatureRequestDto);
        return responseDto.getJwtSignedData();
    }

    private String getJWE(String relyingPartyId, String signedJwt) throws Exception {
        JsonWebEncryption jsonWebEncryption = new JsonWebEncryption();
        jsonWebEncryption.setAlgorithmHeaderValue(KeyManagementAlgorithmIdentifiers.RSA_OAEP_256);
        jsonWebEncryption.setEncryptionMethodHeaderParameter(ContentEncryptionAlgorithmIdentifiers.AES_256_GCM);
        jsonWebEncryption.setPayload(signedJwt);
        jsonWebEncryption.setContentTypeHeaderValue("JWT");
        RSAKey rsaKey = getRelyingPartyPublicKey(relyingPartyId);
        jsonWebEncryption.setKey(rsaKey.toPublicKey());
        jsonWebEncryption.setKeyIdHeaderValue(rsaKey.getKeyID());
        return jsonWebEncryption.getCompactSerialization();
    }

    private RSAKey getRelyingPartyPublicKey(String relyingPartyId) {
        //TODO where to store relying-party public key
        throw new MockIdentityException("jwe-not-implemented");
    }

    private KycAuth saveKycAuthTransaction(String transactionId, String relyingPartyId, String individualId) {
        String kycToken = HelperUtil.generateB64EncodedHash(ALGO_SHA3_256, UUID.randomUUID().toString());
        String psut;
        try {
            psut = HelperUtil.generateB64EncodedHash(ALGO_SHA3_256,
                    String.format(PSUT_FORMAT, individualId, relyingPartyId));
        } catch (Exception e) {
            log.error("Failed to generate PSUT", e);
            throw new MockIdentityException("mock-ida-004");
        }

        KycAuth kycAuth = new KycAuth(kycToken, psut, LocalDateTime.now(ZoneOffset.UTC), Valid.ACTIVE, transactionId,
                individualId);
        if (kycAuth == null)
            throw new MockIdentityException("mock-ida-005");
        return authRepository.save(kycAuth);
    }

    private Map<String, Object> buildKycDataBasedOnPolicy(String individualId, List<String> claims, List<String> locales) {
        Map<String, Object> kyc = new HashMap<>();
        IdentityData identityData = identityService.getIdentity(individualId);
        if (identityData == null) {
            throw new MockIdentityException("mock-ida-001");
        }

        if (CollectionUtils.isEmpty(locales)) {
            locales = Arrays.asList(defaultLanguage);
        }
        boolean singleLanguage = locales.size() == 1;
        for (String claim : claims) {
            switch (claim) {
                case "name":
                	if (identityData.getName() != null) {
                		kyc.putAll(getKycValues(locales, "name", identityData.getName(), singleLanguage));
                        break;
                	} else if (identityData.getFullName() != null) {
                		kyc.putAll(getKycValues(locales, "name", identityData.getFullName(), singleLanguage));
                        break;
                	}
                case "middle_name":
                    kyc.putAll(getKycValues(locales, "middle_name", identityData.getMiddleName(), singleLanguage));
                    break;
                case "given_name":
                    kyc.putAll(getKycValues(locales, "given_name", identityData.getGivenName(), singleLanguage));
                    break;
                case "family_name":
                    kyc.putAll(getKycValues(locales, "family_name", identityData.getFamilyName(), singleLanguage));
                    break;
                case "nickname":
                    kyc.putAll(getKycValues(locales, "nickname", identityData.getNickName(), singleLanguage));
                    break;
                case "preferred_username":
                    kyc.putAll(getKycValues(locales, "name", identityData.getPreferredUsername(), singleLanguage));
                    break;
                case "birthdate":
                    if (identityData.getDateOfBirth() != null) {
                        kyc.put("birthdate", identityData.getDateOfBirth());
                    }
                    break;
                case "gender":
                    kyc.putAll(getKycValues(locales, "gender", identityData.getGender(), singleLanguage));
                    break;
                case "email":
                    if (identityData.getEmail() != null) {
                        kyc.put("email", identityData.getEmail());
                    }
                    break;
                case "phone_number":
                    if (identityData.getPhone() != null) {
                        kyc.put("phone_number", identityData.getPhone());
                    }
                    break;
                case "address":
                    Map<String, Object> addressValues = new HashMap<>();
                    addressValues.putAll(getKycValues(locales, "street_address", identityData.getStreetAddress(), singleLanguage));
                    addressValues.putAll(getKycValues(locales, "locality", identityData.getLocality(), singleLanguage));
                    addressValues.putAll(getKycValues(locales, "region", identityData.getRegion(), singleLanguage));
                    if (identityData.getPostalCode() != null) {
                        addressValues.put("postal_code", identityData.getPostalCode());
                    }
                    addressValues.putAll(getKycValues(locales, "country", identityData.getCountry(), singleLanguage));
                    kyc.put("address", addressValues);
                    break;
                case "zoneinfo":
                    if (identityData.getZoneInfo() != null) {
                        kyc.put("zoneinfo", identityData.getZoneInfo());
                    }
                    break;
                case "locale":
                    if (identityData.getLocale() != null) {
                        kyc.put("picture", identityData.getLocale());
                    }
                    break;
                case "picture":
                    if (identityData.getEncodedPhoto() != null) {
                        kyc.put("picture", identityData.getEncodedPhoto());
                    }
                    break;
                case "individual_id":
                    if (identityData.getIndividualId() != null) {
                        kyc.put("individual_id", identityData.getIndividualId());
                    }
                    break;
            }
        }
        return kyc;
    }

    private Map<String, Object> getKycValues(List<String> locales, String claimName, List<LanguageValue> values, boolean isSingleLanguage) {
        if (values == null) {
            return Collections.emptyMap();
        }
        for (String locale : locales) {
            return values.stream()
                    .filter(v -> v.getLanguage().equalsIgnoreCase(locale) || v.getLanguage().startsWith(locale))
                    .collect(Collectors.toMap(v -> isSingleLanguage ? claimName : claimName + "#" + locale, v -> v.getValue()));
        }
        return Collections.emptyMap();
    }

    public boolean isSupportedOtpChannel(String channel) {
        return channel != null && otpChannels.contains(channel.toLowerCase());
    }
}
