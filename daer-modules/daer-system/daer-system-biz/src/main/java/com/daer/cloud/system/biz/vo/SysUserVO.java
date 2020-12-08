package com.daer.cloud.system.biz.vo;

import com.daer.cloud.common.core.domain.BaseEntity;
import lombok.Data;

import java.util.List;

/**
 * Created by liuwei
 * date 2017-04-11
 */
@Data
public class SysUserVO extends BaseEntity {

    /**
     * 登录账号
     */
    private String userName;

    /**
     * 用户姓名
     */
    private String realName;

    /**
     * 手机号码
     */
    private String mobile;

    /**
     * 用户邮箱
     */
    private String email;

    /**
     * 用户性别（1男 2女 3未知）
     */
    private String sex;

    /**
     * 头像路径
     */
    private String avatar;

    /**
     * 密码
     */
    private String password;

    /**
     * 帐号状态（0正常 1停用）
     */
    private String status;

    /**
     * 部门id
     */
    private Integer deptId;

    /**
     * 角色列表
     */
    private List<Integer> roles;

}
