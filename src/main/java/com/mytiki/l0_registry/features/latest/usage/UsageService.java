package com.mytiki.l0_registry.features.latest.usage;

import com.mytiki.l0_registry.features.latest.config.ConfigDO;
import com.mytiki.l0_registry.features.latest.config.ConfigService;
import com.mytiki.l0_registry.l0.auth.L0AuthAOOrg;
import com.mytiki.l0_registry.l0.auth.L0AuthAOToken;
import com.mytiki.l0_registry.l0.auth.L0AuthAOUser;
import com.mytiki.l0_registry.l0.auth.L0AuthService;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.UsageRecord;
import com.stripe.param.SubscriptionListParams;
import com.stripe.param.UsageRecordCreateOnSubscriptionItemParams;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

import java.lang.invoke.MethodHandles;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class UsageService {
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final UsageRepository repository;
    private final ConfigService configService;
    private final L0AuthService l0AuthService;
    private final int minUsers;
    private final String mauPriceId;
    private final String nuPriceId;

    public UsageService(
            UsageRepository repository,
            ConfigService configService,
            L0AuthService l0AuthService,
            int minUsers,
            String mauPriceId,
            String nuPriceId) {
        this.repository = repository;
        this.configService = configService;
        this.l0AuthService = l0AuthService;
        this.minUsers = minUsers;
        this.mauPriceId = mauPriceId;
        this.nuPriceId = nuPriceId;
    }

    @Transactional
    public void increment(String appId){
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime start = now.truncatedTo(ChronoUnit.DAYS);
        List<UsageDO> found = repository.getAllByConfigAppIdAndCreatedBetween(appId, start, start.plusDays(1));
        long total;
        UsageDO update;
        if(found.isEmpty()){
            Optional<UsageDO> latest = repository.getFirstByConfigAppIdOrderByCreatedDesc(appId);
            update = new UsageDO();
            if(latest.isPresent()){
                total = latest.get().getTotal() + 1;
                update.setConfig(latest.get().getConfig());
            }else{
                update.setConfig(configService.getCreate(appId));
                total = 1L;
            }
            update.setCreated(now);
        }else{
            update = found.get(0);
            total = update.getTotal() + 1;
        }
        update.setTotal(total);
        update.setModified(now);
        repository.save(update);
        report(appId, total, 1L);
    }

    @Transactional
    public void decrement(String appId){
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime start = now.truncatedTo(ChronoUnit.DAYS);
        List<UsageDO> found = repository.getAllByConfigAppIdAndCreatedBetween(appId, start, start.plusDays(1));
        long total;
        UsageDO update;
        if(found.isEmpty()){
            Optional<UsageDO> latest = repository.getFirstByConfigAppIdOrderByCreatedDesc(appId);
            update = new UsageDO();
            if(latest.isPresent()){
                total = latest.get().getTotal() - 1;
                update.setConfig(latest.get().getConfig());
            }else{
                update.setConfig(configService.getCreate(appId));
                total = 0L;
            }
            update.setCreated(now);
        }else{
            update = found.get(0);
            total = update.getTotal() - 1;
        }
        update.setTotal(total);
        update.setModified(now);
        repository.save(update);
        report(appId, total, 0L);
    }

    public List<UsageAO> get(String userId, ZonedDateTime req){
        if(req == null) req = ZonedDateTime.now();
        Set<String> apps = getApps(userId);
        ZonedDateTime end = req.plusDays(1).truncatedTo(ChronoUnit.DAYS);
        Map<ZonedDateTime, List<UsageAOApp>> dateMap = new HashMap<>();

        for (String app : apps) {
            List<UsageDO> days = repository.getAllByConfigAppIdAndCreatedBetween(app, end.minusMonths(1), end);
            days.forEach(usage -> {
                ZonedDateTime day = usage.getCreated().truncatedTo(ChronoUnit.DAYS);
                UsageAOApp rsp = new UsageAOApp();
                rsp.setTotal(usage.getTotal());
                rsp.setAppId(app);
                if(!dateMap.containsKey(day)){
                    dateMap.put(day, List.of(rsp));
                }else{
                    List<UsageAOApp> appList = new ArrayList<>(dateMap.get(day));
                    appList.add(rsp);
                    dateMap.put(day, appList);
                }
            });
        }

        return dateMap.entrySet().stream().map(entry -> {
            UsageAO rsp = new UsageAO();
            rsp.setDate(entry.getKey());
            rsp.setApps(entry.getValue());
            return rsp;
        }).toList();
    }

    private Set<String> getApps(String userId){
        L0AuthAOToken token = l0AuthService.getToken(List.of("auth:internal:read"));
        L0AuthAOUser user = l0AuthService.getUser(userId, token.getAccessToken());
        L0AuthAOOrg org = l0AuthService.getOrg(user.getOrgId(), token.getAccessToken());
        return org.getApps();
    }

    @Async
    void report(String appId, long activeUsers, long newUsers) {
        try {
            if (activeUsers <= minUsers) return;

            ConfigDO config = configService.getBilling(appId);
            if (config.getBillingId() == null) {
                logger.error("No billing id for appId: " + appId);
                return;
            }

            SubscriptionListParams params = new SubscriptionListParams.Builder()
                    .setCustomer(config.getBillingId())
                    .setStatus(SubscriptionListParams.Status.ACTIVE)
                    .build();
            List<Subscription> subscriptions = Subscription.list(params).getData();
            if (subscriptions.isEmpty()) {
                logger.error("No active subscription for: " + config.getBillingId());
                return;
            }

            String mauItem = null;
            String nuItem = null;

            for (SubscriptionItem item : subscriptions.get(0).getItems().getData()) {
                String priceId = item.getPrice().getId();
                if (priceId.equals(mauPriceId)) mauItem = item.getId();
                if (priceId.equals(nuPriceId)) nuItem = item.getId();
            }

            if (mauItem == null || nuItem == null) {
                logger.error("Missing item for: " + config.getBillingId());
                return;
            }

            UsageRecordCreateOnSubscriptionItemParams mauParams = new UsageRecordCreateOnSubscriptionItemParams.Builder()
                    .setQuantity(activeUsers)
                    .build();
            UsageRecordCreateOnSubscriptionItemParams nuParams = new UsageRecordCreateOnSubscriptionItemParams.Builder()
                    .setQuantity(newUsers)
                    .build();
            if(activeUsers > 0) UsageRecord.createOnSubscriptionItem(mauItem, mauParams, null);
            if(newUsers > 0) UsageRecord.createOnSubscriptionItem(nuItem, nuParams, null);
        }catch (StripeException ex){
            logger.error(ex.getMessage(), ex);
        }
    }
}
