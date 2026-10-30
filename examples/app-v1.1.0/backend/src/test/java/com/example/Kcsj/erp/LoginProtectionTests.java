package com.example.Kcsj.erp;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.Kcsj.erp.ErpStore.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginProtectionTests {
    @Test void changingIpCannotBypassAccountFailureLimit() {
        ErpStore store=mock(ErpStore.class);
        AuthService auth=new AuthService(store);
        Map<String,Object> user=map("id","teacher","username","teacher","enabled",true,"role","counselor","passwordHash",auth.hashPassword("ValidPassword2026!"));
        when(store.all("user")).thenReturn(Collections.singletonList(user));
        for(int i=0;i<5;i++) assertEquals(401,assertThrows(ApiException.class,()->auth.login("teacher","wrong",UUID.randomUUID().toString())).status);
        assertEquals(429,assertThrows(ApiException.class,()->auth.login("TEACHER","ValidPassword2026!","another-address")).status);
    }

    @Test void rotatingUsernamesCannotGrowUnlimitedSourceAttempts() {
        ErpStore store=mock(ErpStore.class);
        AuthService auth=new AuthService(store);
        when(store.all("user")).thenReturn(Collections.emptyList());
        boolean limited=false;
        for(int i=0;i<101;i++) {
            final String name="unknown-"+i;
            ApiException failure=assertThrows(ApiException.class,()->auth.login(name,"wrong","same-source"));
            if(failure.status==429){limited=true;break;}
        }
        assertTrue(limited,"Changing account names must not bypass source protection");
    }

    @Test void aSuccessfulLoginClearsFailuresForThatAccountOnly() {
        ErpStore store=mock(ErpStore.class);
        AuthService auth=new AuthService(store);
        Map<String,Object> user=map("id","teacher","username","teacher","enabled",true,"role","counselor","passwordHash",auth.hashPassword("ValidPassword2026!"));
        when(store.all("user")).thenReturn(Collections.singletonList(user));
        for(int round=0;round<2;round++) {
            for(int i=0;i<4;i++) assertEquals(401,assertThrows(ApiException.class,()->auth.login("teacher","wrong","source")).status);
            assertFalse(s(auth.login("teacher","ValidPassword2026!","source"),"token").isEmpty());
        }
    }
}
