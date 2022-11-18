package com.example.authjwt.service;

import com.example.authjwt.data.PasswordRecovery;
import com.example.authjwt.data.Token;
import com.example.authjwt.data.User;
import com.example.authjwt.data.UserDao;
import com.example.authjwt.error.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.relational.core.conversion.DbActionExecutionException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.UUID;

@Service
public class UserService {

    private final UserDao userDao;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserDao userDao, PasswordEncoder passwordEncoder,
                       @Value("${application.security.access-token-secret}") String accessSecretKey
            ,@Value("${application.security.refresh-token-secret}") String refreshSecretKey,
                       MailService mailService) {
        this.userDao = userDao;
        this.passwordEncoder = passwordEncoder;
        this.accessSecretKey = accessSecretKey;
        this.refreshSecretKey = refreshSecretKey;
        this.mailService = mailService;
    }

    private final String accessSecretKey;
    private final String refreshSecretKey;

    private final MailService mailService;


    public User register(String firstName, String lastName, String email,
                         String password, String confirmPassword) {
        if (!Objects.equals(password, confirmPassword)) {
            throw new PasswordNotMatchError();
        }
        User user=null;
        try{
            user=userDao.save(
                    User.of(
                            firstName,
                            lastName,
                            email,
                            passwordEncoder.encode(password)
                    ));
        }catch (DbActionExecutionException e){
            throw new EmailAlreadyExistsError();
        }
        return user;

    }
    public Login login(String email,String password){
        var user=userDao.findUserByEmail(email)
                .orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid password:"))        ;
        if(!passwordEncoder.matches(password, user.getPassword())){
            throw  new InvalidCredentialsError();
        }
        var login = Login.of(user.getId(),accessSecretKey,refreshSecretKey);
        var refreshJwt = login.getRefreshToken();
        user.addToken(new Token(
                refreshJwt.getToken(),
                refreshJwt.getIssuedAt(),
                refreshJwt.getExpiredAt()
        ));
        userDao.save(user);
        return login;
    }

    public User getUserFromToken(String token) {
        return userDao.findById(Jwt.from(token,accessSecretKey).getUserId())
                .orElseThrow(UserNotFoundError::new);
    }
    public Login refreshAccess(String refreshToken) {
        var refreshJwt= Jwt.from(refreshToken,refreshSecretKey);
        var user=userDao.findByIdAndTokensRefreshToken(refreshJwt.getUserId(),
                refreshJwt.getToken(),refreshJwt.getExpiredAt())
                .orElseThrow(UnauthenticatedError::new);
        return Login.of(user.getId(),accessSecretKey, refreshJwt);
    }

    public Boolean logout(String refreshToken){
        var refreshJwt=Jwt.from(refreshToken,refreshSecretKey);
        var user=userDao.findById(refreshJwt.getUserId())
                .orElseThrow(UnauthenticatedError::new);
        var tokenIsRemoved = user
                .removeTokenIf(token -> Objects.equals(token.refreshToken(),refreshToken));
        if (tokenIsRemoved){
            userDao.save(user);
        }
        return tokenIsRemoved;
    }

    public void forget(String email, String orginUrl) {
        var token = UUID.randomUUID().toString()
                .replace("-","");
        var user=userDao.findUserByEmail(email)
                .orElseThrow(UserNotFoundError::new);
        user.addPasswordRecovery(new PasswordRecovery(token));
        mailService.sendForgotMessage(email,token,orginUrl);
        userDao.save(user);
    }

    public void reset(String token, String password, String passwordConfirm) {
        if (!Objects.equals(password, passwordConfirm)) {
            throw new PasswordNotMatchError();
        }
        var user=userDao.findPasswordRecoveryToken(token)
                .orElseThrow(InvalidTokenError::new);
        user.setPassword(passwordEncoder.encode(password));
        user.removePasswordRecoveryIf(passwordRecovery -> Objects.equals(passwordRecovery
                .token(),token));
        userDao.save(user);
    }
}