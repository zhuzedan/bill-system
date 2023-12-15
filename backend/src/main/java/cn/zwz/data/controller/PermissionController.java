package cn.zwz.data.controller;

import cn.zwz.basics.parameter.CommonConstant;
import cn.zwz.basics.redis.RedisTemplateHelper;
import cn.zwz.basics.utils.ResultUtil;
import cn.zwz.basics.utils.SecurityUtil;
import cn.zwz.basics.baseVo.Result;
import cn.zwz.basics.security.permission.MySecurityMetadataSource;
import cn.zwz.data.entity.Permission;
import cn.zwz.data.entity.RolePermission;
import cn.zwz.data.entity.User;
import cn.zwz.data.entity.UserRole;
import cn.zwz.data.service.IPermissionService;
import cn.zwz.data.service.IRolePermissionService;
import cn.zwz.data.service.IUserRoleService;
import cn.zwz.data.utils.VoUtil;
import cn.zwz.data.utils.ZwzNullUtils;
import cn.zwz.data.vo.MenuVo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * @author 郑为中
 */
@RestController
@Api(tags = "菜单/权限管理接口")
@RequestMapping("/zwz/permission")
@CacheConfig(cacheNames = "permission")
@Transactional
public class PermissionController {

    @Autowired
    private SecurityUtil securityUtil;

    @Autowired
    private MySecurityMetadataSource mySecurityMetadataSource;

    @Autowired
    private IRolePermissionService iRolePermissionService;

    @Autowired
    private IPermissionService iPermissionService;

    @Autowired
    private IUserRoleService iUserRoleService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisTemplateHelper redisTemplateHelper;

    @ApiOperation(value = "根据层级查询菜单")
    private List<Permission> getPermissionListByLevel(int level) {
        QueryWrapper<Permission> qw = new QueryWrapper<>();
        qw.eq("level",level);
        qw.orderByAsc("sort_order");
        return iPermissionService.list(qw);
    }
    private List<Permission> getPermissionByUserId(String userId) {
        QueryWrapper<UserRole> urQw = new QueryWrapper<>();
        urQw.eq("user_id",userId);
        List<UserRole> userRoleList = iUserRoleService.list(urQw);
        List<Permission> permissionList = new ArrayList<>();
        for (UserRole userRole : userRoleList) {
            QueryWrapper<RolePermission> rpQw = new QueryWrapper<>();
            rpQw.eq("role_id",userRole.getRoleId());
            List<RolePermission> rolePermissionList = iRolePermissionService.list(rpQw);
            for (RolePermission rolePermission : rolePermissionList) {
                boolean flag = true;
                for (Permission permission : permissionList) {
                    if(Objects.equals(permission.getId(),rolePermission.getPermissionId())) {
                        flag = false;
                        break;
                    }
                }
                if(flag) {
                    permissionList.add(iPermissionService.getById(rolePermission.getPermissionId()));
                }
            }
        }
        return permissionList;
    }

    @RequestMapping(value = "/getMenuList", method = RequestMethod.GET)
    @ApiOperation(value = "查询有权限的菜单")
    public Result<List<MenuVo>> getMenuList(){
        List<MenuVo> menuList = new ArrayList<>();
        User currUser = securityUtil.getCurrUser();
        String keyInRedis = "permission::userMenuList:" + currUser.getId();
        String valueInRedis = redisTemplate.opsForValue().get(keyInRedis);
        if(!ZwzNullUtils.isNull(valueInRedis)){
            menuList = new Gson().fromJson(valueInRedis, new TypeToken<List<MenuVo>>(){}.getType());
            return new ResultUtil<List<MenuVo>>().setData(menuList);
        }
        // 拥有的菜单列表
        List<Permission> list = getPermissionByUserId(currUser.getId());
        // 顶级菜单
        for(Permission permission : list){
            if(CommonConstant.PERMISSION_NAV.equals(permission.getType())&&CommonConstant.LEVEL_ZERO.equals(permission.getLevel())) {
                menuList.add(VoUtil.permissionToMenuVo(permission));
            }
        }
        // 一级菜单
        List<MenuVo> firstMenuList = new ArrayList<>();
        for(Permission permission : list){
            if(Objects.equals(CommonConstant.PERMISSION_PAGE,permission.getType()) && Objects.equals(CommonConstant.LEVEL_ONE,permission.getLevel())) {
                firstMenuList.add(VoUtil.permissionToMenuVo(permission));
            }
        }
        // 二级菜单
        List<MenuVo> secondMenuList = new ArrayList<>();
        for(Permission permission : list){
            if(Objects.equals(CommonConstant.PERMISSION_PAGE,permission.getType()) && Objects.equals(CommonConstant.LEVEL_TWO,permission.getLevel())) {
                secondMenuList.add(VoUtil.permissionToMenuVo(permission));
            }
        }
        // 按钮
        List<MenuVo> buttonPermissions = new ArrayList<>();
        for(Permission permission : list){
            if(Objects.equals(CommonConstant.PERMISSION_OPERATION,permission.getType()) && Objects.equals(CommonConstant.LEVEL_THREE,permission.getLevel())) {
                buttonPermissions.add(VoUtil.permissionToMenuVo(permission));
            }
        }
        // 有权限的二级菜单
        for(MenuVo vo : secondMenuList){
            List<String> permTypes = new ArrayList<>();
            for(MenuVo menuVo : buttonPermissions){
                if(Objects.equals(vo.getId(),menuVo.getParentId())){
                    permTypes.add(menuVo.getButtonType());
                }
            }
            vo.setPermTypes(permTypes);
        }
        // 二连一
        for(MenuVo vo : firstMenuList) {
            List<MenuVo> secondMenu = new ArrayList<>();
            for(MenuVo menuVo : secondMenuList){
                if(Objects.equals(vo.getId(),menuVo.getParentId())) {
                    secondMenu.add(menuVo);
                }
            }
            vo.setChildren(secondMenu);
        }
        // 一连顶
        for(MenuVo vo : menuList) {
            List<MenuVo> firstMenu = new ArrayList<>();
            for(MenuVo menuVo : firstMenuList){
                if(Objects.equals(vo.getId(),menuVo.getParentId())) {
                    firstMenu.add(menuVo);
                }
            }
            vo.setChildren(firstMenu);
        }
        redisTemplate.opsForValue().set(keyInRedis, new Gson().toJson(menuList), 10L, TimeUnit.DAYS);
        return new ResultUtil<List<MenuVo>>().setData(menuList);
    }

    @RequestMapping(value = "/search", method = RequestMethod.GET)
    @ApiOperation(value = "搜索菜单")
    public Result<List<Permission>> searchPermissionList(@RequestParam String title){
        QueryWrapper<Permission> qw = new QueryWrapper<>();
        qw.like("title",title);
        qw.orderByAsc("sort_order");
        return new ResultUtil<List<Permission>>().setData(iPermissionService.list(qw));
    }

    @ApiOperation(value = "根据父ID查询菜单")
    private List<Permission> getPermissionListByParentId(String parentId) {
        QueryWrapper<Permission> qw = new QueryWrapper<>();
        qw.eq("parent_id",parentId);
        qw.orderByAsc("sort_order");
        return iPermissionService.list(qw);
    }

    @RequestMapping(value = "/getAllList", method = RequestMethod.GET)
    @ApiOperation(value = "获取权限菜单树")
    @Cacheable(key = "'allList'")
    public Result<List<Permission>> getAllList(){
        // 顶级菜单列表
        List<Permission> list0 = getPermissionListByLevel(0);
        for(Permission p0 : list0){
            // 一级
            List<Permission> list1 = getPermissionListByParentId(p0.getId());
            p0.setChildren(list1);
            // 二级
            for(Permission p1 : list1){
                List<Permission> children1 = getPermissionListByParentId(p1.getId());
                p1.setChildren(children1);
                // 三级
                for(Permission p2 : children1){
                    List<Permission> children2 = getPermissionListByParentId(p2.getId());
                    p2.setChildren(children2);
                }
            }
        }
        return new ResultUtil<List<Permission>>().setData(list0);
    }

    @RequestMapping(value = "/delByIds", method = RequestMethod.POST)
    @ApiOperation(value = "删除菜单")
    @CacheEvict(key = "'menuList'")
    public Result<Object> delByIds(@RequestParam String[] ids){
        for(String id : ids){
            QueryWrapper<RolePermission> qw = new QueryWrapper<>();
            qw.like("permission_id",id);
            long rolePermissionCount = iRolePermissionService.count(qw);
            if(rolePermissionCount > 0L) {
                Permission permission = iPermissionService.getById(id);
                if(permission == null) {
                    return ResultUtil.error("该空菜单正在被角色使用，不能删除");
                }
                return ResultUtil.error(permission.getTitle() + "菜单正在被角色使用，不能删除");
            }
        }
        for(String id:ids){
            iPermissionService.removeById(id);
        }
        mySecurityMetadataSource.loadResourceDefine();
        redisTemplate.delete("permission::allList");
        return ResultUtil.success();
    }

    @RequestMapping(value = "/add", method = RequestMethod.POST)
    @ApiOperation(value = "添加")
    @CacheEvict(key = "'menuList'")
    public Result<Permission> add(Permission permission){
        if(Objects.equals(CommonConstant.PERMISSION_OPERATION,permission.getType())) {
            QueryWrapper<Permission> perQw = new QueryWrapper<>();
            perQw.eq("title",permission.getTitle());
            long permissionByTitleCount = iPermissionService.count();
            if(permissionByTitleCount > 0L){
                return new ResultUtil<Permission>().setErrorMsg("名称已存在");
            }
        }
        iPermissionService.saveOrUpdate(permission);
        mySecurityMetadataSource.loadResourceDefine();
        redisTemplate.delete("permission::allList");
        return new ResultUtil<Permission>().setData(permission);
    }

    @RequestMapping(value = "/edit", method = RequestMethod.POST)
    @ApiOperation(value = "编辑")
    public Result<Permission> edit(Permission permission){
        if(Objects.equals(CommonConstant.PERMISSION_OPERATION,permission.getType())) {
            Permission p = iPermissionService.getById(permission.getId());
            if(!Objects.equals(p.getTitle(),permission.getTitle())) {
                QueryWrapper<Permission> perQw = new QueryWrapper<>();
                perQw.eq("title",permission.getTitle());
                long permissionCount = iPermissionService.count(perQw);
                if(permissionCount > 0L){
                    return new ResultUtil<Permission>().setErrorMsg("名称已存在");
                }
            }
        }
        iPermissionService.saveOrUpdate(permission);
        mySecurityMetadataSource.loadResourceDefine();
        Set<String> keysUser = redisTemplateHelper.keys("user:" + "*");
        redisTemplate.delete(keysUser);
        Set<String> keysUserMenu = redisTemplateHelper.keys("permission::userMenuList:*");
        redisTemplate.delete(keysUserMenu);
        redisTemplate.delete("permission::allList");
        return new ResultUtil<Permission>().setData(permission);
    }
}
