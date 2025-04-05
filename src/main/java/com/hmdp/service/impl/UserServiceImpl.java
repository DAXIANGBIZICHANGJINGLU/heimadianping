package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)){
            //1.1如果不符合，返回错误信息
            return Result.fail("手机格式有问题");
        }

        //2.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

//        //3.保存验证码到session
//        session.setAttribute("code", code);
        // 3. 保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //4.发送验证码
        log.info("验证码为：{}",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1. 验证手机号
        //1.2 手机号错误，返回错误信息
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号不对");
        }
//        //2. 从session获取验证码
//        Object cacheCode = session.getAttribute("code");
        // 2. 从redis获取验证码
        String cacheCode = stringRedisTemplate.opsForValue()
                .get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
//        //3. 比较验证码是否正确
//        if (cacheCode == null || !cacheCode.toString().equals(code)){
//            return  Result.fail("验证码错误");
//        }
        // 3. 比较验证码是否正确
        if (cacheCode == null || !cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }

        //4. 根据手机号查询用户是否存在
        User user = query().eq("phone", phone).one();
        // 5. 不存在，创建用户，将用户保存到数据库
        if (user == null){
            user = createUserWithPhone(phone);
        }
//        // 6. 存在，保存用户到session
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        // 6. 保存用户信息到redis中
        // 6.1 随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 6.2 将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 6.3 存储到redis
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        // 6.4 设置有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.SECONDS);
        // 7. 返回token
        return Result.ok(token);
    }

    public User createUserWithPhone(String phone){
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
