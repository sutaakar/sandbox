package com.redhat.service.smartevents.manager.dns.openshift;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.services.route53.model.Change;
import com.amazonaws.services.route53.model.ChangeAction;
import com.amazonaws.services.route53.model.ChangeBatch;
import com.amazonaws.services.route53.model.ChangeResourceRecordSetsRequest;
import com.amazonaws.services.route53.model.InvalidChangeBatchException;
import com.amazonaws.services.route53.model.RRType;
import com.amazonaws.services.route53.model.ResourceRecord;
import com.amazonaws.services.route53.model.ResourceRecordSet;
import com.redhat.service.smartevents.infra.utils.Constants;
import com.redhat.service.smartevents.manager.ShardService;
import com.redhat.service.smartevents.manager.dns.DnsService;
import com.redhat.service.smartevents.manager.dns.KnativeBrokerPathBuilder;

import io.smallrye.mutiny.Uni;

public class DnsServiceOpenshiftImpl implements DnsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DnsServiceOpenshiftImpl.class);

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final DnsConfigOpenshiftProvider dnsConfigOpenshiftProvider;

    private final ShardService shardService;

    public DnsServiceOpenshiftImpl(ShardService shardService, DnsConfigOpenshiftProvider dnsConfigOpenshiftProvider) {
        LOGGER.info("Using Openshift implementation for DNS - a CNAME record is going to be created on AWS Route53 for each BridgeIngress");
        this.shardService = shardService;
        this.dnsConfigOpenshiftProvider = dnsConfigOpenshiftProvider;
    }

    @Override
    public String buildBridgeEndpoint(String bridgeId, String customerId) {
        // Need to append the path to the broker until https://github.com/knative/eventing/issues/6467 gets fixed
        return Constants.HTTPS_SCHEME + buildBridgeHost(bridgeId) + KnativeBrokerPathBuilder.build(customerId, bridgeId);
    }

    /**
     * Creates a new DNS record in the hosted zone with the "simple" router policy. For complex DNS resolution we have to change this accordingly.
     *
     * @param bridgeId
     * @return
     */
    @Override
    public Boolean createDnsRecord(String bridgeId) {
        LOGGER.info("Creating DNS zone for bridge '{}'", bridgeId);
        ResourceRecordSet resourceRecordSet = buildResourceRecordSet(buildBridgeHost(bridgeId), bridgeId);

        // idempotency guarantee : if a resource set exists Route 53 updates it with the values in the request.
        Change addStateChange = new Change(ChangeAction.UPSERT, resourceRecordSet);
        ChangeBatch changeBatch = new ChangeBatch(List.of(addStateChange));
        return Uni.createFrom().future(
                dnsConfigOpenshiftProvider.getAmazonRouteClient()
                        .changeResourceRecordSetsAsync(new ChangeResourceRecordSetsRequest().withChangeBatch(changeBatch).withHostedZoneId(dnsConfigOpenshiftProvider.getHostedZoneId())))
                .onItem()
                .transformToUni(x -> Uni.createFrom().item(true))
                .await().atMost(DEFAULT_TIMEOUT);
    }

    @Override
    public Boolean deleteDnsRecord(String bridgeId) {
        LOGGER.info("Deleting DNS zone for bridge '{}'", bridgeId);
        ResourceRecordSet resourceRecordSet = buildResourceRecordSet(buildBridgeHost(bridgeId), bridgeId);
        Change addStateChange = new Change(ChangeAction.DELETE, resourceRecordSet);
        ChangeBatch changeBatch = new ChangeBatch(List.of(addStateChange));
        return Uni.createFrom().future(
                dnsConfigOpenshiftProvider.getAmazonRouteClient().changeResourceRecordSetsAsync(
                        new ChangeResourceRecordSetsRequest()
                                .withChangeBatch(changeBatch)
                                .withHostedZoneId(
                                        dnsConfigOpenshiftProvider.getHostedZoneId())))
                // idempotency guarantee : if the Dns zone does not exist the client we ignore the InvalidChangeBatchException raised by the aws client
                .onFailure(InvalidChangeBatchException.class)
                .recoverWithNull()
                .onItem()
                .transformToUni(x -> Uni.createFrom().item(true))
                .await().atMost(DEFAULT_TIMEOUT);
    }

    private ResourceRecordSet buildResourceRecordSet(String recordName, String bridgeId) {
        ResourceRecordSet resourceRecordSet = new ResourceRecordSet()
                .withName(recordName)
                .withTTL(60L);
        resourceRecordSet.setType(RRType.CNAME);

        ResourceRecord resourceRecord = new ResourceRecord(shardService.getAssignedShard(bridgeId).getRouterCanonicalHostname());
        resourceRecordSet.setResourceRecords(List.of(resourceRecord));
        return resourceRecordSet;
    }

    private String buildBridgeHost(String bridgeId) {
        return bridgeId + dnsConfigOpenshiftProvider.getSubdomain();
    }
}
