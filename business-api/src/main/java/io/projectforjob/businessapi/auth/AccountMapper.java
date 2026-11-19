package io.projectforjob.businessapi.auth;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
interface AccountMapper {

    @Select("""
            SELECT id, login_name, display_name, password_hash, status
            FROM app_user
            WHERE login_name = #{loginName}
            """)
    Account findByLoginName(String loginName);

    @Insert("""
            INSERT INTO app_user (login_name, display_name, password_hash, status)
            VALUES (#{loginName}, #{displayName}, #{passwordHash}, 'ACTIVE')
            ON DUPLICATE KEY UPDATE id = id
            """)
    void createDemoAccount(@Param("loginName") String loginName, @Param("displayName") String displayName,
            @Param("passwordHash") String passwordHash);

    record Account(long id, String loginName, String displayName, String passwordHash, String status) {
        boolean active() {
            return "ACTIVE".equals(status);
        }
    }
}
