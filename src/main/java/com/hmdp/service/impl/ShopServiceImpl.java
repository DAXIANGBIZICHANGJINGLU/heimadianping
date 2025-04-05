package com.hmdp.service.impl;

import cn.hutool.cache.Cache;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ShopTypeMapper shopTypeMapper;
    @Autowired
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
//        Shop shop = queryWithPassThrough(id);
        // 解决缓存穿透，使用自定义工具类
        Shop shop = cacheClient.queryWithPassThrough(
                CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 缓存击穿,使用互斥锁解决
//        Shop shop = queryWithMutex(id);
//        if (shop == null){
//            return Result.fail("店铺不存在呢");
//        }
        // 缓存击穿，使用逻辑过期解决
//        Shop shop = queryWithLogicalExpire(id);
        // 使用工具类逻辑过期，解决缓存击穿
        cacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return Result.ok(shop);
//        String key = CACHE_SHOP_KEY + id;
//        //1. 从redis查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        //2. 判断是否存在
//        if (StrUtil.isNotBlank(shopJson)){
//            //3. 存在，直接返回
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return Result.ok(shop);
//        }
//        // 判断命中的是否是空值
//        //if (shopJson != null){
//        if (shopJson == ""){
//            // 返回一个错误信息
//            return Result.fail("店铺信息不存在");
//        }
//        //4. 不存在，根据id查询数据库
//        Shop shop = getById(id);
//        //5. 不存在，返回错误
//        if (shop == null){
//            // 解决缓存穿透：向redis缓存写入空值
//            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return Result.fail("店铺不存在");
//        }
//        //6. 存在，写入redis
//        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        //7. 返回
//        return Result.ok(shop);
    }

    private Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1. 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)){
            //3. 存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 判断命中的是否是空值
        //if (shopJson != null){
        if (shopJson == ""){
            // 查询redis缓存未命中，返回一个错误信息
            return null;
        }
        // 4. 实现缓存重建
        // 4.1 获取互斥锁
        String lockkey = LOCK_SHOP_KEY;
        Shop shop = null;
        try {
            boolean islock = trylock(lockkey);
            // 4.2 判断是否成功
            if (!islock){
                // 4.3 失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 4.4 成功，根据id查询数据库
            shop = getById(id);
            // 模拟重建的延时
            Thread.sleep(200);
            // 双检，如果缓存存在，无需重建缓存
            String shopJson2 = stringRedisTemplate.opsForValue().get(key);
            //2. 判断是否存在
            if (StrUtil.isNotBlank(shopJson2)){
                //3. 存在，直接返回
                Shop shop2 = JSONUtil.toBean(shopJson2, Shop.class);
                return shop2;
            }
            // 判断命中的是否是空值
            //if (shopJson != null){
            if (shopJson2 == ""){
                // 查询redis缓存未命中，返回一个错误信息
                return null;
            }

            // 判断命中的是否是空值
            //5. 不存在，返回错误
            if (shop == null){
                // 解决缓存穿透：向redis缓存写入空值
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6. shop存在，且不为空，写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7. 释放互斥锁
             unlock(lockkey);
        }
        //8. 返回
        return shop;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1. 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在
        if (StrUtil.isBlank(shopJson)){
            //3. 不存在，返回null
            return null;
        }
        // 判断命中的是否是空值
        //if (shopJson != null){
        if (shopJson == ""){
            // 返回一个错误信息
            return null;
        }
        // 命中，不为空值，把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            // 未过期，返回店铺信息
            return shop;
        }
        // 已经过期，需要重建缓存
        // 重建缓存
        // 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = trylock(lockKey);
        // 判断是否获取到锁
        if (isLock){
            // 成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
                
            });
        }
        // 返回过期的商铺信息
        return shop;
    }

//    public Shop queryWithPassThrough(Long id){
//        String key = CACHE_SHOP_KEY + id;
//        //1. 从redis查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        //2. 判断是否存在
//        if (StrUtil.isNotBlank(shopJson)){
//            //3. 存在，直接返回
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//        }
//        // 判断命中的是否是空值
//        //if (shopJson != null){
//        if (shopJson != ""){
//            // 返回一个错误信息
//            return null;
//        }
//        //4. 不存在，根据id查询数据库
//        Shop shop = getById(id);
//        //5. 不存在，返回错误
//        if (shop == null){
//            // 解决缓存穿透：向redis缓存写入空值
//            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return null;
//        }
//        //6. 存在，写入redis
//        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        //7. 返回
//        return shop;
//    }


    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();

        if (id == null){
            return Result.fail("这店铺id有问题");
        }
        // 1. 更新数据库
        updateById(shop);
        // 2. 删除redis缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    private boolean trylock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);

    }

    public void saveShop2Redis(Long id, Long expirSeconds) throws InterruptedException {
        // 查询店铺数据
        Shop shop = getById(id);
        // 模拟缓存重建的延时
        Thread.sleep(200);
        // 使用RedisData封装逻辑国企时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expirSeconds));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
}
