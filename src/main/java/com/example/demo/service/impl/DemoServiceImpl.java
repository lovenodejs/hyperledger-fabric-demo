package com.example.demo.service.impl;


import com.example.demo.fabric.sdk.testutils.TestConfig;
import com.example.demo.fabric.sdkintegration.SampleOrg;
import com.example.demo.fabric.sdkintegration.SampleStore;
import com.example.demo.fabric.sdkintegration.SampleUser;
import com.example.demo.service.DemoService;

import com.google.protobuf.ByteString;
import io.netty.util.internal.ConcurrentSet;
import org.hyperledger.fabric.protos.common.Ledger;
import org.hyperledger.fabric.protos.ledger.rwset.kvrwset.KvRwset;
import org.hyperledger.fabric.sdk.*;
import org.hyperledger.fabric.sdk.security.CryptoSuite;
import org.hyperledger.fabric_ca.sdk.HFCAClient;
import org.hyperledger.fabric_ca.sdk.RegistrationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Paths;
import java.util.*;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;

@Service
public class DemoServiceImpl implements DemoService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoServiceImpl.class);

    private static final TestConfig testConfig = TestConfig.getConfig();
    private static SampleOrg sampleOrg;
    private static HFClient client = HFClient.createNewInstance();
    private static SampleStore sampleStore;
    private static HFCAClient ca;
    private static SampleUser admin;
    private static Channel channel;
    private static ChaincodeID chaincodeID;
    private static Orderer orderer;
    private static Peer peer;

    static {
        LOGGER.info("java.io.tmpdir: " + System.getProperty("java.io.tmpdir"));

        try {
            //////////////////////////////////////////////////////初始化，保存文件
            client.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());
            File sampleStoreFile = new File(System.getProperty("java.io.tmpdir") + "/HFCSampletest.properties");
            sampleStore = new SampleStore(sampleStoreFile);
            //sampleOrg的名称为peerOrg1
            sampleOrg = testConfig.getIntegrationTestsSampleOrgs().iterator().next();
            sampleOrg.setCAClient(HFCAClient.createNewInstance(sampleOrg.getCALocation(), sampleOrg.getCAProperties()));
            ca = sampleOrg.getCAClient();
            chaincodeID = ChaincodeID.newBuilder()
                    .setName("demo_cc_go23")
                    .setVersion("1")
                    .setPath("demo_cc")
                    .build();
            //创建order这里，并没有发送到fabric
            String orderName = sampleOrg.getOrdererNames().iterator().next();
            orderer = client.newOrderer(orderName, sampleOrg.getOrdererLocation(orderName));


            //////////////////////////////////////////////////////设置peerOrgAdmin
            String sampleOrgName = sampleOrg.getName();
            String sampleOrgDomainName =sampleOrg.getDomainName();
            //参数为name, org, MSPID, privateKeyFile,certificateFile
            SampleUser peerOrgAdmin = sampleStore.getMember(sampleOrgName + "Admin", sampleOrgName, sampleOrg.getMSPID(),
                    findFile_sk(Paths.get(testConfig.getTestChannlePath(), "crypto-config/peerOrganizations/",
                            sampleOrgDomainName, format("/users/Admin@%s/msp/keystore", sampleOrgDomainName)).toFile()),
                    Paths.get(testConfig.getTestChannlePath(), "crypto-config/peerOrganizations/", sampleOrgDomainName,
                            format("/users/Admin@%s/msp/signcerts/Admin@%s-cert.pem", sampleOrgDomainName, sampleOrgDomainName)).toFile());
            //该peerOrgAdmin是用来创建channel，添加peer，和安装chaincode
            sampleOrg.setPeerAdmin(peerOrgAdmin);
            //创建peer
            String peerName = sampleOrg.getPeerNames().iterator().next();
            String peerLocation = sampleOrg.getPeerLocation(peerName);
            //创建peer这步不会连接到fabric
            peer = client.newPeer(peerName, peerLocation);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public String initial() {
        String result = "";
        try {
            LOGGER.info("createChannel");
            createChannel();
            LOGGER.info("peerJoinChannel");
            peerJoinChannel();
            LOGGER.info("initialChannel");
            initialChannel();
            LOGGER.info("installChaincode");
            installChaincode();
            LOGGER.info("instantiateChaincode");
            instantiateChaincode();
            result = "启动成功";
        } catch (Exception ex) {
            result = "启动失败";
            LOGGER.error(ex.getMessage(), ex);
        }

        return result;

    }

    private static Map<String, Integer> map = new HashMap<>();

    private static String getField(Object object, Class clazz) throws Exception {
        StringBuffer output = new StringBuffer();
        output.append("\n");
        for(Field field: clazz.getDeclaredFields()) {
            field.setAccessible(true);
            String name = field.getName();
            Object value = field.get(object);
            if(null != value) {
                if(value instanceof Collection) {
                    Collection collection = (Collection) value;
                    for(Object each : collection) {
                        printObject(each);
                    }
                }
//                if(value instanceof String) {
//                    output.append("string_: " + value).append("\n");
//                } if(value instanceof Integer) {
//                    output.append("integer_: " + value).append("\n");
//                } if(value instanceof Long) {
//                    output.append("long_: " + value).append("\n");
//                }
                if (value instanceof char[]) {
                    char[] chars = (char[]) value;
                    output.append("chars_: " + new String(chars)).append("\n");
                }
                else {
                    output.append(name + ": " + value).append("\n");
                }
            }
        }
        return output.toString();
    }

    private static void printObject(Object object) throws Exception {
        StringBuffer output = new StringBuffer();
        output.append(getField(object, object.getClass()));
        Class superClass = object.getClass().getSuperclass();
        if(null != superClass) {
            output.append(getField(object, superClass));
        }
        String className = object.getClass().getSimpleName();
        int count = 0;
        if(map.containsKey(className)) {
            count = map.get(className);
            count++;
            map.put(className, count);
        } else {
            map.put(className, count);
        }
        LOGGER.info("===============================" + className + " " + count);
        LOGGER.info(output.toString());
    }


    private static void blockView(Channel channel) throws Exception{
        printObject(channel);

        BlockchainInfo blockchainInfo = channel.queryBlockchainInfo();
        printObject(blockchainInfo);

        for(int i = 0; i < blockchainInfo.getHeight(); i++) {
            BlockInfo blockInfo = channel.queryBlockByNumber(i);
            printObject(blockInfo.getBlock());

            for(BlockInfo.EnvelopeInfo envelopeInfo : blockInfo.getEnvelopeInfos()) {
                printObject(envelopeInfo);

                if(envelopeInfo.getType() != BlockInfo.EnvelopeType.TRANSACTION_ENVELOPE) {
                    continue;
                }

                BlockInfo.TransactionEnvelopeInfo transactionEnvelopeInfo = (BlockInfo.TransactionEnvelopeInfo) envelopeInfo;

                for(BlockInfo.TransactionEnvelopeInfo.TransactionActionInfo transactionActionInfo : transactionEnvelopeInfo.getTransactionActionInfos()) {
                    printObject(transactionActionInfo);

                    for(int j = 0; j < transactionActionInfo.getEndorsementsCount(); j++) {
                        BlockInfo.EndorserInfo endorserInfo = transactionActionInfo.getEndorsementInfo(j);
                        printObject(endorserInfo);
                    }

                    for(int j = 0; j < transactionActionInfo.getChaincodeInputArgsCount(); j++) {
                        printObject(transactionActionInfo.getChaincodeInputArgs(j));
                    }

                    TxReadWriteSetInfo txReadWriteSetInfo = transactionActionInfo.getTxReadWriteSet();

                    for(TxReadWriteSetInfo.NsRwsetInfo nsRwsetInfo : txReadWriteSetInfo.getNsRwsetInfos()) {
                        printObject(nsRwsetInfo);
                        KvRwset.KVRWSet kvrwSet = nsRwsetInfo.getRwset();

                        for(KvRwset.KVRead kvRead : kvrwSet.getReadsList()) {
                            printObject(kvRead);
                        }

                        for(KvRwset.KVWrite kvWrite : kvrwSet.getWritesList()) {
                            printObject(kvWrite);
                        }

                    }
                }
            }
        }
        map.clear();
    }


    private static void enrollAdmin() throws Exception {
        ca.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());
        admin = sampleStore.getMember("admin", sampleOrg.getName());
        //enrollment这步会在，本地生成keypair和csr，然后发送到fabric-ca进行签名（enrollment会包含，私钥和数字证书）
        Enrollment adminEnrollment = ca.enroll(admin.getName(), "adminpw");
        admin.setEnrollment(adminEnrollment);
        admin.setMPSID(sampleOrg.getMSPID());
        //该机构的admin
        sampleOrg.setAdmin(admin);
    }

    private static void enrollMember() throws Exception{
        SampleUser user = sampleStore.getMember("user1", sampleOrg.getName());
        //affiliation
        RegistrationRequest registrationRequest = new RegistrationRequest(user.getName(), "org1.department1");
        //发送用户名+部门名，到fabric-ca进行注册，返回值只有一个密码
        String enrollmentSecret = ca.register(registrationRequest, admin);
        user.setEnrollmentSecret(enrollmentSecret);
        //获取enrollment
        //如果重复注册，则会返回 {"success":false,"result":null,"errors":[{"code":0,"message":"Identity 'user1' is already registered"}],"messages":[]}
        Enrollment userEnrollment = ca.enroll(user.getName(), user.getEnrollmentSecret());
        user.setEnrollment(userEnrollment);
        user.setMPSID(sampleOrg.getMSPID());
        sampleOrg.addUser(user);
    }


    private static void createChannel() throws Exception {
        //userContext不能为空
        client.setUserContext(sampleOrg.getPeerAdmin());
        ChannelConfiguration channelConfiguration = new ChannelConfiguration(new File("src/main/java/com/example/demo/fabric/fixture/sdkintegration/e2e-2Orgs/channel/foo.tx"));
        //创建channel，是需要连接到fabric
        //参数channelName, orderer, channelConfiguration, channelConfigurationSignatures
        //如果重复创建，会返回New channel foo error. StatusValue 400. Status BAD_REQUEST
        channel = client.newChannel("foo", orderer, channelConfiguration,
                client.getChannelConfigurationSignature(channelConfiguration, sampleOrg.getPeerAdmin())
        );
        printObject(channel);
        //channel的创建，其实是通过orderer.sendTransaction实现的
        //会创建创世区块getGenesisBlock，并且需要添加共识节点orderer
    }

    private static void peerJoinChannel() throws Exception{
        //channel添加peer节点，会连接到fabric
        //重复添加的话，会提示status: 500, message: Cannot create ledger from genesis block, due to LedgerID already exists
        channel.joinPeer(peer);
        printObject(peer);
        printObject(channel);
        //通过Envelope发去orderer来创建创世块
        sampleOrg.addPeer(peer);
    }

    private static void initialChannel() throws Exception {
        //Starts the channel. event hubs will connect
        channel.initialize();
        printObject(channel);
    }

    private static void installChaincode() throws Exception {
        Collection<ProposalResponse> responses;
        client.setUserContext(sampleOrg.getPeerAdmin());
        InstallProposalRequest installProposalRequest = client.newInstallProposalRequest();
        installProposalRequest.setChaincodeID(chaincodeID);
        installProposalRequest.setChaincodeSourceLocation(new File("src/main/java/com/example/demo/chaincode"));
        installProposalRequest.setChaincodeVersion("1");
        printObject(installProposalRequest);
        responses = client.sendInstallProposal(installProposalRequest, sampleOrg.getPeers());
        printObject(responses);
        Collection<ProposalResponse> successful = new LinkedList<>();
        Collection<ProposalResponse> failed = new LinkedList<>();
        for(ProposalResponse proposalResponse : responses) {
            if(proposalResponse.getStatus() == ProposalResponse.Status.SUCCESS) {
                successful.add(proposalResponse);
            } else {
                failed.add(proposalResponse);
            }
        }
    }

    private static void instantiateChaincode() throws Exception{
        Collection<ProposalResponse> responses;
        InstantiateProposalRequest instantiateProposalRequest = client.newInstantiationProposalRequest();
        instantiateProposalRequest.setProposalWaitTime(testConfig.getProposalWaitTime());
        instantiateProposalRequest.setChaincodeID(chaincodeID);
        instantiateProposalRequest.setFcn("init");
        instantiateProposalRequest.setArgs(new String[] {"a", "500", "b", "200"});
        Map<String, byte[]> tm = new HashMap<>();
        tm.put("HyperLedgerFabric", "InstantiateProposalRequest:JavaSDK".getBytes(UTF_8));
        tm.put("method", "InstantiateProposalRequest".getBytes(UTF_8));
        instantiateProposalRequest.setTransientMap(tm);
        ChaincodeEndorsementPolicy chaincodeEndorsementPolicy = new ChaincodeEndorsementPolicy();
        chaincodeEndorsementPolicy.fromYamlFile(new File("src/main/java/com/example/demo/fabric/fixture/sdkintegration/chaincodeendorsementpolicy.yaml"));
        instantiateProposalRequest.setChaincodeEndorsementPolicy(chaincodeEndorsementPolicy);
        printObject(instantiateProposalRequest);
        //发送实例化请求到fabric
        responses = channel.sendInstantiationProposal(instantiateProposalRequest);
        printObject(responses);

        //////////////////////////////////////////////////////发送结果到orderer
        Collection<ProposalResponse> successful = new LinkedList<>();
        Collection<ProposalResponse> failed = new LinkedList<>();
        for(ProposalResponse proposalResponse : responses) {
            if(proposalResponse.isVerified() && proposalResponse.getStatus() == ProposalResponse.Status.SUCCESS) {
                successful.add(proposalResponse);
            } else {
                failed.add(proposalResponse);
            }
        }
        printObject(successful);
        printObject(channel.getOrderers());
        channel.sendTransaction(successful, channel.getOrderers());
    }

    private static void check() throws Exception {
        if(null == client.getUserContext()) {
            client.setUserContext(sampleOrg.getPeerAdmin());
            printObject(sampleOrg.getPeerAdmin());
            printObject(sampleOrg.getPeerAdmin().getEnrollment());
        }
        if(null == channel) {
            channel = client.newChannel("foo");
        }
        if(channel.getOrderers().isEmpty()) {
            channel.addOrderer(orderer);
        }
        if(channel.getPeers().isEmpty()) {
            channel.addPeer(peer);
        }
        if(!channel.isInitialized()) {
            channel.initialize();
        }

    }

    @Override
    public String startEvent() {
        String result = "startEvent 错误";
        try {
            executeTransaction(new String[] {"updateIsEventStarted", "1"});
            result = "startEvent 成功";
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    @Override
    public String endEvent() {
        String result = "endEvent 错误";
        try {
            executeTransaction(new String[] {"updateIsEventStarted", "0"});
            result = "endEvent 成功";
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    @Override
    public String isEventStarted() {
        try {
            return executeQuery(new String[] {"isEventStarted"});
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "isEventStarted 错误";
    }

    @Override
    public String query(String key) {
        try {
            return executeQuery(new String[] {"query", key});
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "query 错误";
    }

    @Override
    public String buyLuckyNumber(String key, String number) {
        String result = "buyLuckyNumber 错误";
        try {
            executeTransaction(new String[] {"buyLuckyNumber", key, number});
            result = "buyLuckyNumber 成功";
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    @Override
    public String inputLuckyNumber(String number) {
        String result = "inputLuckyNumber 错误";
        try {
            executeTransaction(new String[] {"inputLuckyNumber", number});
            result = "inputLuckyNumber 成功";
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }


    private static String executeQuery(String[] args) throws Exception {
        check();

        QueryByChaincodeRequest queryByChaincodeRequest = client.newQueryProposalRequest();
        queryByChaincodeRequest.setArgs(args);
        queryByChaincodeRequest.setFcn("invoke");
        queryByChaincodeRequest.setChaincodeID(chaincodeID);
        printObject(chaincodeID);

        Map<String, byte[]> tm2 = new HashMap<>();
        tm2.put("HyperLedgerFabric", "QueryByChaincodeRequest:JavaSDK".getBytes(UTF_8));
        tm2.put("method", "QueryByChaincodeRequest".getBytes(UTF_8));
        queryByChaincodeRequest.setTransientMap(tm2);
        printObject(queryByChaincodeRequest);

        String result = "";
        Collection<ProposalResponse> responses = channel.queryByChaincode(queryByChaincodeRequest);
        for(ProposalResponse proposalResponse: responses) {
            printObject(proposalResponse);
            if(proposalResponse.isVerified() && proposalResponse.getStatus() == ProposalResponse.Status.SUCCESS) {
                String payload = proposalResponse.getProposalResponse().getResponse().getPayload().toStringUtf8();
                LOGGER.info("payload: " + payload);
                if(result.length() > 0) {
                    result += ",";
                }
                result += payload;
            }
        }
        blockView(channel);

        return result;

    }

    private static void executeTransaction(String[] args) throws Exception {
        check();

        TransactionProposalRequest transactionProposalRequest = client.newTransactionProposalRequest();
        transactionProposalRequest.setChaincodeID(chaincodeID);
        transactionProposalRequest.setFcn("invoke");
        transactionProposalRequest.setArgs(args);

        Map<String, byte[]> tm2 = new HashMap<>();
        tm2.put("HyperLedgerFabric", "TransactionProposalRequest:JavaSDK".getBytes(UTF_8));
        tm2.put("method", "TransactionProposalRequest".getBytes(UTF_8));
        tm2.put("result", ":)".getBytes(UTF_8));  /// This should be returned see chaincode.
        transactionProposalRequest.setTransientMap(tm2);


        Collection<ProposalResponse> successful = new LinkedList<>();
        Collection<ProposalResponse> failed = new LinkedList<>();
        Collection<ProposalResponse> responses = channel.sendTransactionProposal(transactionProposalRequest);
        for(ProposalResponse proposalResponse : responses) {
            if(proposalResponse.isVerified() && proposalResponse.getStatus() == ProposalResponse.Status.SUCCESS) {
                successful.add(proposalResponse);
            } else {
                failed.add(proposalResponse);
            }
        }
        channel.sendTransaction(successful);
    }



    private static File findFile_sk(File directory) {

        File[] matches = directory.listFiles((dir, name) -> name.endsWith("_sk"));

        if (null == matches) {
            throw new RuntimeException(format("Matches returned null does %s directory exist?", directory.getAbsoluteFile().getName()));
        }

        if (matches.length != 1) {
            throw new RuntimeException(format("Expected in %s only 1 sk file but found %d", directory.getAbsoluteFile().getName(), matches.length));
        }

        return matches[0];

    }


    public static void main(String[] args) throws Exception{
        //////////////////////////////////////////////////////注册ca（可以多次注册，每次生成的证书和密钥对都不一样)
//        enrollAdmin();
        //////////////////////////////////////////////////////会员注册（同一个用户名，不可以重复注册）
//        enrollMember();
        //////////////////////////////////////////////////////创建channel（不可以重复创建）
//        createChannel();
        //////////////////////////////////////////////////////把peer加入到channel（不可以重复加入）
//        peerJoinChannel();
        //////////////////////////////////////////////////////初始化channel
//        initialChannel();
        //////////////////////////////////////////////////////安装chaincode
//        installChaincode();
        //////////////////////////////////////////////////////实例化chaincode
//        instantiateChaincode();
        //////////////////////////////////////////////////////查询结果
//        Thread.sleep(5000);
//        queryChaincode();
        //////////////////////////////////////////////////////转发结果
//        transferChaincode();
    }
}
