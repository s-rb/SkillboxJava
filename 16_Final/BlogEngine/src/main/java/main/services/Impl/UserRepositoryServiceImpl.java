package main.services.Impl;

import main.api.request.*;
import main.api.response.*;
import main.model.entities.*;
import main.model.repositories.CaptchaRepository;
import main.model.repositories.PostRepository;
import main.model.repositories.UserRepository;
import main.services.interfaces.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpSession;
import javax.xml.bind.DatatypeConverter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class UserRepositoryServiceImpl implements UserRepositoryService {

    private static final char[] SYMBOLS_FOR_GENERATOR = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    @Value("${user.password.restore_key_length}")
    private int keySize;
    @Value("${user.image.root_folder}")
    private String rootPathToUploadAvatars;
    @Value("${user.password.validation_regex}")
    private String passwordValidationRegex;
    @Value("${user.email.validation_regex}")
    private String emailValidationRegex;
    @Value("${user.image.upload_folder}")
    private String uploadFolder;
    @Value("${user.image.avatars_folder}")
    private String avatarsFolder;
    @Value("${user.image.format}")
    private String imageFormat;
    @Value("${user.image.max_size}")
    private int maxPhotoSizeInBytes;
    @Value("${user.image.upload_timeout_ms}")
    private int timeoutToUploadPhotoMS;
    @Value("${user.timeout_edit_profile}")
    private int timeoutToFinishEditProfileMS;
    @Value("${user.password.restore_pass_message_string}")
    private String restorePassMessageString;
    @Value("${user.password.restore_message_subject}")
    private String restoreMessageSubject;
    @Value("${user.password.hashing_algorithm}")
    private String hashingAlgorithm;

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CaptchaRepositoryService captchaRepositoryService;
    @Autowired
    private GlobalSettingsRepositoryService globalSettingsRepositoryService;
    @Autowired
    private PostVoteRepositoryService postVoteRepositoryService;
    @Autowired
    private PostRepositoryService postRepositoryService;
    @Autowired
    private JavaMailSender emailSender;
    @Autowired
    private CaptchaRepository captchaRepository;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private FileSystemService fileSystemService;

    private Map<String, Integer> sessionIdToUserId = new HashMap<>(); // Храним сессию и ID пользователя, по заданию не в БД

    @Override
    public ResponseEntity<User> getUser(int id) {
        Optional<User> optionalUser = userRepository.findById(id);
        return optionalUser.isPresent() ?
                new ResponseEntity<>(optionalUser.get(), HttpStatus.OK)
                : ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
    }

    @Override
    public ResponseEntity<ResponseApi> login(LoginRequest loginRequest, HttpSession session) {
        String email = loginRequest.getEmail();
        String password = loginRequest.getPassword();
        User user = userRepository.getUserByEmail(email);
        if (user == null) {
            return new ResponseEntity<>(new ResponseBoolean(false), HttpStatus.BAD_REQUEST);
        }
        String hashedPassToCheck = getHashedString(password);
        if (user.getStoredHashPass().equals(hashedPassToCheck)) { // пароль совпал по хэшу
            sessionIdToUserId.put(session.getId().toString(), user.getId()); // Запоминаем пользователя и сессию
            return new ResponseEntity<>(new ResponseLogin(user), HttpStatus.OK);
        } else {
            return new ResponseEntity<>(new ResponseBoolean(false), HttpStatus.BAD_REQUEST);
        }
    }

    @Override
    public ResponseEntity<ResponseApi> checkAuth(HttpSession session) {
        if (!sessionIdToUserId.containsKey(session.getId().toString())) {
            return new ResponseEntity<>(new ResponseBoolean(false), HttpStatus.UNAUTHORIZED);
        } else {
            int userId = sessionIdToUserId.get(session.getId().toString());
            User user = getUser(userId).getBody();
            if (user != null) {
                return new ResponseEntity<>(new ResponseLogin(user), HttpStatus.OK);
            } else {        // "Ошибка! Пользователь найден в сессиях, однако отсутствует в БД!";
                return new ResponseEntity<>(new ResponseBoolean(false), HttpStatus.UNAUTHORIZED);
            }
        }
    }

    @Override
    public ResponseEntity<ResponseApi> restorePassword(RestorePassRequest restorePassRequest) {
        String email = restorePassRequest.getEmail();
        if (!isEmailValid(email)) {
            return new ResponseEntity<>(new ResponseBoolean(false), HttpStatus.BAD_REQUEST);
        }
        User user = userRepository.getUserByEmail(email);
        if (user == null) {
            return new ResponseEntity<>(new ResponseBoolean(false), HttpStatus.BAD_REQUEST);
        }
        String restoreCode = generateRandomString();
        user.setCode(restoreCode); // запоминаем код в базе
        userRepository.save(user);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(email);
        message.setSubject(restoreMessageSubject);
        message.setText(restorePassMessageString + restoreCode);
        this.emailSender.send(message);
        return new ResponseEntity<>(new ResponseBoolean(true), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<ResponseApi> changePassword(ChangePasswordRequest changePasswordRequest) {
        String code = changePasswordRequest.getCode();
        String password = changePasswordRequest.getPassword();
        String captcha = changePasswordRequest.getCaptcha();
        String captchaSecret = changePasswordRequest.getCaptchaSecret();
        if (code == null || password == null || captcha == null || captchaSecret == null ||
                code.isBlank() || password.isBlank() || captcha.isBlank() || captchaSecret.isBlank()) {
            return new ResponseEntity<>(new ResponseBadReqMsg("Введены не все требуемые параметры"), HttpStatus.BAD_REQUEST);
        }
        User user = userRepository.getUserByCode(code);
        boolean isCodeValid = (user != null);
        boolean isPassValid = isPasswordValid(password);
        boolean isCaptchaCodeValid = isCaptchaValid(captcha, captchaSecret);
        if (!isCodeValid || !isPassValid || !isCaptchaCodeValid) {
            return new ResponseEntity<>(new ResponseFailChangePass(isCodeValid, isPassValid, isCaptchaCodeValid), HttpStatus.BAD_REQUEST);
        }
        String newHashedPassword = getHashedString(password);
        user.setHashedPassword(newHashedPassword);
        userRepository.save(user);
        return new ResponseEntity<>(new ResponseBoolean(true), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<ResponseApi> register(RegisterRequest registerRequest) {
        String email = registerRequest.getEmail();
        if (email == null || email.isBlank()) {
            return new ResponseEntity<>(new ResponseBadReqMsg("Email не может быть пустым"), HttpStatus.BAD_REQUEST);
        }
        String name = email.replaceAll("@.+", "");
        String password = registerRequest.getPassword();
        String captcha = registerRequest.getCaptcha();
        String captchaSecret = registerRequest.getCaptchaSecret();
        if (name.isBlank() || password == null || captcha == null || captchaSecret == null
                || password.isBlank() || captcha.isBlank() || captchaSecret.isBlank()) {
            return new ResponseEntity<>(new ResponseBadReqMsg("В запросе отсутствуют требуемые параметры"), HttpStatus.BAD_REQUEST);
        }
        // Проверка уникальности имени и email
        boolean isNameValid = (userRepository.getUserByName(name) == null);
        boolean isEmailValid = (userRepository.getUserByEmail(email.toLowerCase()) == null && isEmailValid(email));
        boolean isCaptchaCodeValid = isCaptchaValid(captcha, captchaSecret);
        boolean isPassValid = isPasswordValid(password);
        if (!isNameValid || !isPassValid || !isCaptchaCodeValid || !isEmailValid) {
            return new ResponseEntity<>(new ResponseFailSignUp(isNameValid, isPassValid,
                    isCaptchaCodeValid, isEmailValid), HttpStatus.BAD_REQUEST);
        }
        // Все проверки прошли, регистрация
        String hashedPassword = getHashedString(password);
        User user = new User(false, LocalDateTime.now(), name, email, hashedPassword);
        userRepository.save(user); // И можно получить id
        return new ResponseEntity<>(new ResponseBoolean(true), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<ResponseApi> editProfile(EditProfileRequest editProfileRequest, HttpSession session) {
        Byte removePhoto = editProfileRequest.getRemovePhoto();
        String email = editProfileRequest.getEmail();
        String name = editProfileRequest.getName();
        String password = editProfileRequest.getPassword();
//        String captcha = editProfileRequest.getCaptcha();
//        String captchaSecret = editProfileRequest.getCaptchaSecret();
        // проверка авторизации
        Integer userId = getUserIdBySession(session);
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(null);
        User user = getUser(userId).getBody();
        if (user == null)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);  // Ошибка, пользователь не найден, а сессия есть
        // Пользователь найден, проверяем введенные значения
        boolean isNameValid = true;
        boolean isEmailValid = true;
//        boolean isCaptchaCodeValid = isCaptchaValid(captcha, captchaSecret);
        boolean isPassValid = true;
        boolean isPhotoValid = true;
        // проверяем и устанавливаем новые параметры
        if (password != null && !password.isBlank()) {
            isPassValid = isPasswordValid(password);
            String hashedPassword = getHashedString(password);
            if (isPassValid) user.setHashedPassword(hashedPassword);
        }
        if (name != null && !name.isBlank() && !name.equals(user.getName())) {
            isNameValid = (userRepository.getUserByName(name) == null);
            if (isNameValid) user.setName(name);
        }
        if (email != null && !email.isBlank()) {
            isEmailValid = (userRepository.getUserByEmail(email.toLowerCase()) == null && isEmailValid(email));
            if (isEmailValid) user.setEmail(email);
        }
        if (removePhoto != null && removePhoto == 1) {
            String currentPhoto = user.getPhoto();
            fileSystemService.deleteFileByPath(currentPhoto);
            user.setPhoto(null);
        }
        if (editProfileRequest instanceof EditProfileWithPhotoRequest) { // Если переданный запрос содержит фото
            MultipartFile photo = ((EditProfileWithPhotoRequest) editProfileRequest).getPhoto();
            if (photo != null) {
                if (photo.getSize() > maxPhotoSizeInBytes && photo.getSize() < 0) {
                    System.err.println("Размер фото " + photo + " " + photo.getSize() + " " + photo.getOriginalFilename());
                    isPhotoValid = false;
                } else if (photo.getSize() == 0) {
                    isPhotoValid = true;
                }
                else {
                    // Если у юзера уже есть фото, удаляем его
                    if (user.getPhoto() != null) fileSystemService.deleteFileByPath(user.getPhoto());
                    String directoryPath = getDirectoryToUpload();          // папка для загрузки нового фото
                    String imageName = getRandomImageName();
                    if (fileSystemService.createDirectoriesByPath(directoryPath)) {
                        String fileDestPath = directoryPath + "/" + imageName;
                        while (Files.exists(Paths.get(fileDestPath))) {
                            imageName = getRandomImageName();
                            fileDestPath = directoryPath + "/" + imageName;
                        }
                        try {
                            photo.transferTo(Paths.get(directoryPath, imageName));
                            user.setPhoto(fileDestPath);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
        if (!isNameValid || !isPassValid
//                || !isCaptchaCodeValid
                || !isEmailValid || !isPhotoValid) {
            return new ResponseEntity<>(new ResponseFailEditProfile(isEmailValid, isNameValid,
                    isPassValid
                    , true // isCaptchaCodeValid Временно заглушка, пока непонятно будет ли капча
                    ,isPhotoValid), HttpStatus.BAD_REQUEST);
        }
        userRepository.save(user);
        return new ResponseEntity<>(new ResponseBoolean(true), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<?> getMyStatistics(HttpSession session) {
        Integer userId = getUserIdBySession(session);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(null);
        }
        User user = getUser(userId).getBody();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null); // Ошибка, пользователь не найден, а сессия есть
        }
        LocalDateTime firstPostTime = null;
        Set<Post> myPosts = user.getPosts();
        int postsCount = myPosts.size();
        int allLikesCount = 0;
        int allDislikeCount = 0;
        int viewsCount = 0;
        for (Post p : myPosts) {
            LocalDateTime currentPostTime = p.getTime();
            if (firstPostTime == null) {
                firstPostTime = currentPostTime;
            } else if (firstPostTime.isAfter(currentPostTime)) {
                firstPostTime = currentPostTime;
            }
            viewsCount += p.getViewCount();
            Set<PostVote> currentPostVotes = p.getPostVotes();
            for (PostVote like : currentPostVotes) {
                if (like.getValue() == 1) {
                    allLikesCount += 1;
                } else if (like.getValue() == -1) {
                    allDislikeCount += 1;
                }
            }
        }
        String firstPublicationDate;
        firstPublicationDate = firstPostTime == null ? "Еще не было"
                : firstPostTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        ResponseStatistics responseStatistics = new ResponseStatistics(postsCount, allLikesCount, allDislikeCount,
                viewsCount, firstPublicationDate);
        return new ResponseEntity<>(responseStatistics.getMap(), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<?> getAllStatistics(HttpSession session) {
        HashSet<GlobalSettings> settingsSet = globalSettingsRepositoryService.getAllGlobalSettingsSet();
        boolean isStatisticsIsPublic = false;
        for (GlobalSettings s : settingsSet) {
            if (s.getName().toUpperCase().equals(GlobalSettingsRepositoryService.STATISTICS_IS_PUBLIC)) {
                if (s.getValue().toUpperCase().equals("YES")) {
                    isStatisticsIsPublic = true;
                } else if (s.getValue().toUpperCase().equals("NO")) {
                    isStatisticsIsPublic = false;
                }
            }
        }
        Integer userId = getUserIdBySession(session);
        if (!isStatisticsIsPublic && userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(null);
        }
        int postsCount = postRepository.countAllPosts();
        int allLikesCount = postRepository.countAllLikes();
        int allDislikeCount = postRepository.countAllDislikes();
        int viewsCount = postRepository.countAllViews();
        String firstPublicationDate = postsCount < 1 ? "Еще не было публикаций" : postRepository
                .getFirstPublicationDate().toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        ResponseStatistics responseStatistics = new ResponseStatistics(postsCount, allLikesCount, allDislikeCount,
                viewsCount, firstPublicationDate);
        return new ResponseEntity<>(responseStatistics.getMap(), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<ResponseApi> logout(HttpSession session) {
        String sessionId = session.getId();
        if (!sessionIdToUserId.containsKey(sessionId)) {
            return ResponseEntity.status(HttpStatus.OK).body(new ResponseBoolean(true));
        } // По условию всегда возвращает true
        else {
            sessionIdToUserId.remove(sessionId);
            session.invalidate();
            return ResponseEntity.status(HttpStatus.OK).body(new ResponseBoolean(true));
        }
    }

    @Override
    public ArrayList<User> getAllUsersList() {
        return new ArrayList<>(userRepository.findAll());
    }

    @Override
    public Integer getUserIdBySession(HttpSession session) {
        return sessionIdToUserId.get(session.getId().toString());
    }

    private String generateRandomString() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < keySize; i++) {
            builder.append(SYMBOLS_FOR_GENERATOR[(int) (Math.random() * (SYMBOLS_FOR_GENERATOR.length - 1))]);
        }
        return builder.toString();
    }

    private boolean isPasswordValid(String passwordToCheck) {
        return (passwordToCheck != null && passwordToCheck.matches(passwordValidationRegex));
    }

    private boolean isEmailValid(String emailToCheck) {
        return (emailToCheck != null && emailToCheck.matches(emailValidationRegex));
    }

    private String getDirectoryToUpload() {
        StringBuilder builder = new StringBuilder(rootPathToUploadAvatars).append("/")
                .append(uploadFolder).append("/").append(avatarsFolder);
        return builder.toString();
    }

    private String getRandomImageName() {
        String randomHash = getHashedString(String.valueOf(Math.pow(Math.random(), 100 * Math.random())));
        return randomHash + "." + imageFormat; // имя файла задаем хэшем
    }

    private Boolean isCaptchaValid(String captcha, String captchaSecret) {
        CaptchaCode captchaCode = captchaRepository.getCaptchaBySecretCode(captchaSecret);
        return captchaCode != null && captchaCode.getCode().equals(captcha);
    }

    private String getHashedString(String stringToHash) {
        try {
            MessageDigest md = MessageDigest.getInstance(hashingAlgorithm);
            md.update(stringToHash.getBytes());
            byte[] digest = md.digest();
            return DatatypeConverter.printHexBinary(digest).toUpperCase();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return null;
    }
}
