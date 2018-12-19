package com.yb.boot.security.jwt.controller;

import com.alibaba.fastjson.JSONObject;
import com.yb.boot.security.jwt.auth.tools.AntiViolenceCheckTools;
import com.yb.boot.security.jwt.auth.tools.JwtTokenTools;
import com.yb.boot.security.jwt.common.CommonDic;
import com.yb.boot.security.jwt.common.JwtProperties;
import com.yb.boot.security.jwt.common.ResultInfo;
import com.yb.boot.security.jwt.model.SysUser;
import com.yb.boot.security.jwt.request.RefreshToken;
import com.yb.boot.security.jwt.request.UserRegister;
import com.yb.boot.security.jwt.request.UserRequest;
import com.yb.boot.security.jwt.response.JwtToken;
import com.yb.boot.security.jwt.response.UserDetailsInfo;
import com.yb.boot.security.jwt.service.SecurityJwtService;
import com.yb.boot.security.jwt.utils.LoginUserUtils;
import com.yb.boot.security.jwt.utils.RealIpGetUtils;
import com.yb.boot.security.jwt.utils.VerifyCodeUtils;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.validator.constraints.Length;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * author yangbiao
 * Description:控制层代码
 * date 2018/11/30
 */
@Controller
@Validated
@CrossOrigin//处理跨域
//@RequestMapping("/auth")//添加一层路径是必要的,
//我现在只在需要放开的接口添加一层共同的路径,便于放开路径/auth/login和/auth/verifyCode,
//这种只放开部分接口,在类上加一层路径没什么用处,你还得逐个放开,所以对于需要放开的加就可以了
//这种方式还有一个弊端,就是因为放开的是/auth/**,所以随便一个路径只要在/security下就可以直接跳过
//拦截,从而报error错误,信息会到error页面去,而不是提示用户去登录,故而感觉还是直接放开指定接口即可,
//反正接口也不多,而且不容易因为漏掉/security而出现的各种问题.
public class SecurityJwtController {
    public static final Logger log = LoggerFactory.getLogger(SecurityJwtController.class);

    @Autowired
    private JwtProperties jwtProperties;
    @Autowired
    private SecurityJwtService securityJwtService;
    @Autowired
    private RedisTemplate<String, Serializable> redisTemplate;

    @GetMapping("/toLogin")
    public String toLogin() {
        return "/login";
    }

    @GetMapping("/loginBack")
    public String loginBack() {
        return "/loginBack";
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/index")
    public String index() {
        return "/index";
    }

    //    @PreAuthorize("isAuthenticated()")
    @PostMapping("/addUser")
    public String addUser(@Valid UserRegister userRegister) {
        securityJwtService.addUser(userRegister);
        return "/layout";
    }

    //如果想要走自己写的登出接口,接口不能为/logout,这个默认会走配置那里的.logout()--配置已删除
    //需要登录才能退出,不要在配置文件那里放开此接口,不然会包Context未空异常
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/customLogout")
    public String customLogout(HttpServletResponse response, HttpServletRequest request) {
        //清空用户的登录
        SecurityContext context = SecurityContextHolder.getContext();
        if (context == null) {
            log.info("获取到的Context为空");
            return "forward:/toLogin";
        }
        Authentication auth = context.getAuthentication();
        //正确的登录姿势
        if (auth != null) {
            //调用api登出
            new SecurityContextLogoutHandler().logout(request, response, auth);
        }
        return "forward:/toLogin";
    }

    @GetMapping("/queryUserList")
    @ResponseBody
    public JSONObject queryUserList(@RequestParam(defaultValue = "1") int page,@RequestParam(defaultValue = "25") int rows,
                                    @RequestParam(defaultValue = "") String username) {
        JSONObject result = securityJwtService.queryUserList(page,rows,username);
        return result;
    }

    @PostMapping("/updateUser")
    @ResponseBody
    public JSONObject updateUser(@RequestParam(defaultValue = "") @NotBlank(message = "id不能为空")
                                     //因为页面设置的最多能选20行,也就是20个id,所以
                                     //长度不会超过20*36+20(20个逗号实际是19,id的UUID的长度为36实为32)
                                     @Length(max = 750, message = "用户id过长") String id) {
        String result = securityJwtService.updateUser(id);
        return (JSONObject) JSONObject.toJSON(ResultInfo.success(result));
    }

    @PostMapping("/deleteUser")
    @ResponseBody
    public JSONObject deleteUser(@RequestParam(defaultValue = "") @NotBlank(message = "id不能为空")
                                     //因为页面设置的最多能选20行,也就是20个id,所以
                                     //长度不会超过20*36+20(20个逗号实际是19,id的UUID的长度为36实为32)
                                     @Length(max = 750, message = "用户id过长") String ids) {
        String result = securityJwtService.deleteUser(ids);
        return (JSONObject) JSONObject.toJSON(ResultInfo.success(result));
    }

    @GetMapping("/findUserById")
    public ResultInfo<SysUser> findUserById(@NotBlank(message = "id不能为空")
                                            @Length(max = 100, message = "用户id过长")
                                            @RequestParam(defaultValue = "") String id) {
        //实测通过swagger不填写id的时候会先抛出MissingServletRequestParameterException异常的id不存在的英文
        //所以根本来不到注解@NotBlank这里,刚开始还以为是@NotBlank抛出的,竟然没有中文,实测发现还没有到
        //ConstraintViolationException这个捕捉中文的异常,通过设置参数默认值为空字符串,能够正常获取到中文提示
        SysUser result = securityJwtService.findUserById(id);
        return ResultInfo.success(result);
    }

    @GetMapping("/deleteUserById")
    public ResultInfo<String> deleteUserById(@NotBlank(message = "id不能为空")
                                             @Length(max = 100, message = "用户id过长")
                                             @RequestParam(defaultValue = "") String id) {
        //实测通过swagger不填写id的时候会先抛出MissingServletRequestParameterException异常的id不存在的英文
        //所以根本来不到注解@NotBlank这里,刚开始还以为是@NotBlank抛出的,竟然没有中文,实测发现还没有到
        //ConstraintViolationException这个捕捉中文的异常,通过设置参数默认值为空字符串,能够正常获取到中文提示
        securityJwtService.deleteUserById(id);
        return ResultInfo.success("操作成功");
    }

    //@PreFilter和@PostFilter用来对集合类型的参数或者返回值进行过滤
    //@Secured("admin,manager")//不支持Spring EL表达式
    //@PostAuthorize("hasAuthority('')")//方法调用之后执行认证
    @PreAuthorize("hasAuthority('query')")
    @GetMapping("/yes")
    @ResponseBody
    public ResultInfo<List<String>> yes() {
        return ResultInfo.success(new ArrayList<String>() {{
            add("yes");
            add(LoginUserUtils.getUsername());
            System.err.println(LoginUserUtils.getUserDetails());
        }});
    }

    //@PreFilter和@PostFilter用来对集合类型的参数或者返回值进行过滤
    //hasPermission需要自定义来实现,反正SimpleGrantedAuthority构造名称就叫role
    //所以可以把权限当成角色来看就行了,只是有些角色含有很多小角色而已,为了好看
    //一点可以使用hasAuthority代替hasRole,但是效果都一样,而且自动补全会把所有的
    //权限角色模块都显示出来供你选择
    @PreAuthorize("hasAuthority('update')")
    @GetMapping("/hello")
    @ResponseBody
    public ResultInfo<List<String>> hello() {
        return ResultInfo.success(new ArrayList<String>() {{
            add("hello");
        }});
    }

    //@PreFilter和@PostFilter用来对集合类型的参数或者返回值进行过滤
    @PreAuthorize("hasAuthority('" + CommonDic.ROLE_ + "admin')")//hasAuthority和hasRole功能一样
    @GetMapping("/world")
    @ResponseBody
    public ResultInfo<List<String>> world() {
        return ResultInfo.success(new ArrayList<String>() {{
            add("world");
        }});
    }

    //@PreFilter和@PostFilter用来对集合类型的参数或者返回值进行过滤
    //@Secured("admin,manager")//不支持Spring EL表达式
    //@PostAuthorize("hasAuthority('')")//方法调用之后执行认证
    @PreAuthorize("hasAuthority('" + CommonDic.MODULE_ + "center')")//方法执行之前执行认证
    @GetMapping("/users")
    @ResponseBody
    public ResultInfo<List<String>> users() {
        return ResultInfo.success(new ArrayList<String>() {{
            add("rose");
            add("jack");
            add("mark");
        }});
    }

    //@PreFilter和@PostFilter用来对集合类型的参数或者返回值进行过滤
    //@Secured("admin,manager")//不支持Spring EL表达式
    //@PostAuthorize("hasAuthority('')")//方法调用之后执行认证
    //@PreAuthorize("principal.username.equals(#username)")//通过principal的写法就是解析不了表达式
    //@PreAuthorize("principal.username.toString().equals(#username)")//字符串化也不得行
    @PreAuthorize("authentication.name.equals(#username)")
    //这个直接在安全上下文取的就可以,或许是因为解析token的时候,只设置了SecurityContent而没有UserDetails
    @GetMapping("/list")
    @ResponseBody
    public ResultInfo<List<String>> list(@RequestParam @Length(min = 10, message = "长度过长") String username) {
        return ResultInfo.success(new ArrayList<String>() {{
            add("rose1");
            add("jack2");
            add("mark3");
        }});
    }

    //@PreFilter和@PostFilter用来对集合类型的参数或者返回值进行过滤
    //@Secured("admin,manager")//不支持Spring EL表达式
    //@PostAuthorize("hasAuthority('')")//方法调用之后执行认证
    @GetMapping("/getMessage")
    @ResponseBody
    public ResultInfo<String> getMessage() {
        return ResultInfo.success("我不需要权限就可以访问哦,在接口方法上放开,而不是通过antMatch");
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/refreshToken")
    @ResponseBody
    public ResultInfo<JwtToken> refreshToken(@Valid @RequestBody RefreshToken refreshToken, HttpServletResponse response) {
        //判断token的合法性并解析出用户详细信息
        UserDetailsInfo detailsInfo = JwtTokenTools.getUserByJwt(refreshToken.getAccessToken(), jwtProperties);
        //生成token信息
        String accessToken = JwtTokenTools.createAccessToken(detailsInfo, jwtProperties.getExpireSeconds(), response, jwtProperties);
        //封装token返回
        if (StringUtils.isNotBlank(accessToken)) {
            JwtToken jwtToken = new JwtToken();
            jwtToken.setAccessToken(CommonDic.TOKEN_PREFIX + accessToken);
            jwtToken.setRefreshToken(CommonDic.TOKEN_PREFIX + refreshToken.getRefreshToken());
            jwtToken.setTokenExpire(jwtProperties.getExpireSeconds());
            //返回数据
            return ResultInfo.success(jwtToken);
        }
        return ResultInfo.error("刷新token失败");

    }

    @PostMapping("/frontLogin")
    public String frontLogin(@Valid UserRequest userRequest, HttpServletRequest request,
                             HttpServletResponse response) {
        getJwtTokenResultInfo(userRequest, request, response, CommonDic.FROM_FRONT);
        //登录成功之后跳转
        return "/index";
    }

    @PostMapping("/backLogin")
    public String backLogin(@Valid UserRequest userRequest, HttpServletRequest request,
                            HttpServletResponse response, Model model) {
        ResultInfo<JwtToken> jwtTokenResultInfo = getJwtTokenResultInfo(userRequest, request, response, CommonDic.FROM_BACK);
        JwtToken data = jwtTokenResultInfo.getData();
        String accessToken = data.getAccessToken();
        model.addAttribute("token", accessToken);
        //登录成功之后跳转
        return "/layout";
    }

    /**
     * 登录公共部门代码抽取
     */
    private ResultInfo<JwtToken> getJwtTokenResultInfo(UserRequest userRequest, HttpServletRequest request,
                                                       HttpServletResponse response, String from) {
        //获取用户名
        String username = userRequest.getUsername();
        //获取用户真实地址
        String ipAddress = RealIpGetUtils.getIpAddress(request);
        //拼接存储key用以存储信息到redis
        String key = CommonDic.LOGIN_SIGN_PRE + ipAddress + username;
        //检测用户登录次数是否超过指定次数,超过就不再往下验证用户信息
        AntiViolenceCheckTools.checkLoginTimes(redisTemplate, key);
        //检测用户名登录失败次数--->根据自己的需求添加我这里就用一个,其他的注释
        //AntiViolenceCheckTools.usernameOneDayForbidden(redisTemplate, username);
        //检测登录用户再次ip的登录失败的次数
        //AntiViolenceCheckTools.ipForbidden(request,redisTemplate);
        //进行用户登录认证
        JwtToken jwtToken = securityJwtService.authUser(userRequest, from, response, request);
        //成功登录后清除用户登录失败(允许次数类)的次数
        AntiViolenceCheckTools.checkLoginTimesClear(redisTemplate, key);
        //成功登录后清零此用户名登录失败的次数
        //AntiViolenceCheckTools.usernameOneDayClear(redisTemplate, username);
        //成功登录后清零此ip登录失败的次数
        //AntiViolenceCheckTools.ipForbiddenClear(request, redisTemplate);
        //返回数据
        return ResultInfo.success(jwtToken);
    }

    //----------------------验证码都是提供生成接口和校验接口有前端请求生成和校验------------------------------

    @GetMapping("/verifyCodeCheck")
    @ResponseBody
    public String verifyCodeCheck(String verifyCode, HttpServletRequest request) {
        if (StringUtils.isNotBlank(verifyCode)) {
            //获取服务ip
            String ipAddress = RealIpGetUtils.getIpAddress(request);
            String key = CommonDic.VERIFYCODE_SIGN_PRE + ipAddress;
            //获取redis上的存储的(最新的)验证码
            String code = (String) redisTemplate.opsForValue().get(key);
            //校验验证码
            if (StringUtils.isNotBlank(code) && code.contains("@&")) {
                code = code.split("@&")[1];
                if (verifyCode.toLowerCase().equals(code.toLowerCase())) {
                    return "true";
                }
            } else {
                return "expir";
            }
        }
        return "false";
    }

    @GetMapping("/verifyCode")
    public void verifyCode(HttpServletResponse response, HttpServletRequest request) {
        Integer times;
        //获取服务ip
        String ipAddress = RealIpGetUtils.getIpAddress(request);
        //拼接存储redis的key
        String key = CommonDic.VERIFYCODE_SIGN_PRE + ipAddress;
        //获取验证码及其刷新次数信息
        String code = (String) redisTemplate.opsForValue().get(key);
        if (StringUtils.isNotBlank(code) && code.contains("@&")) {
            times = Integer.valueOf(code.split("@&")[0]);
            //判断刷新次数
            if (times > CommonDic.REQUEST_MAX_TIMES) {
                //结束程序--等待redis上的数据过期再重新再来
                return;
            }
            //增加次数
            times++;
        } else {
            times = 0;
        }
        //获取字符验证码
        String verifyCode = VerifyCodeUtils.generateVerifyCode(CommonDic.VERIFYCODE_AMOUNT);
        try {
            VerifyCodeUtils.outputImage(80, 30, response.getOutputStream(), verifyCode);
            //存储验证码并设置过期时间为5分钟--限制点击的次数,防止恶意点击
            redisTemplate.opsForValue().set(key, times + "@&" + verifyCode, CommonDic.VERIFYCODE_EXPIRED, TimeUnit.SECONDS);
        } catch (IOException e) {
            log.info("验证码输出异常");
            e.printStackTrace();
        }
    }

}
