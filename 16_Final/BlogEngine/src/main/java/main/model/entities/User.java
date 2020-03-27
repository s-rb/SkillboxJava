package main.model.entities;

import javax.persistence.*;
import java.io.Serializable;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users")
public class User implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(nullable = false)
    private int id;

    // @Size(max = 3)
    //@Column(name = "      ", columnDefinition="TINYINT(3) UNSIGNED default '40'")
    @Column(name = "is_moderator", nullable = false)        // является ли пользователь модератором  //columnDefinition="TINYINT(1)"
    private boolean isModerator;            // (может ли править глобальные настройки сайта и модерировать посты)

    @Column(name = "reg_time", nullable = false, columnDefinition = "DATETIME")
    private Timestamp registrationTime;

    @Column(nullable = false, columnDefinition = "VARCHAR(255)", unique = true)
    private String name;

    @Column(nullable = false, columnDefinition = "VARCHAR(255)", unique = true)
    private String email;

    // https://howtodoinjava.com/security/how-to-generate-secure-password-hash-md5-sha-pbkdf2-bcrypt-examples/
    // https://www.codota.com/code/java/methods/org.riotfamily.core.security.auth.HibernateUserDao/hashPassword
    // https://www.baeldung.com/java-password-hashing
    // https://askdev.ru/q/autentifikaciya-hibernate-bez-paroley-hranyaschihsya-v-obychnom-tekste-214168/
    @Column(name = "password", nullable = false, columnDefinition = "VARCHAR(255)")
    private String hashedPassword;

    @Column(nullable = true, columnDefinition = "VARCHAR(255)")
    private String code;

    @Column(nullable = true, columnDefinition = "TEXT")
    private String photo;

    public User(boolean isModerator, Timestamp registrationTime, String name, String email, String hashedPassword) {
        this.isModerator = isModerator;
        this.registrationTime = registrationTime;
        this.name = name;
        this.email = email;
        this.hashedPassword = hashedPassword;
    }

    // Доп поля - связи
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<Post> posts = new HashSet<Post>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<PostVote> postVotes = new HashSet<PostVote>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<PostComment> postComments = new HashSet<PostComment>();

    @OneToMany(mappedBy = "moderatorId", cascade = CascadeType.ALL)
    private Set<Post> postsModerated = new HashSet<Post>();

    public User() {
    }

    // Геттеры и сеттеры
    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public boolean isModerator() {
        return isModerator;
    }

    public void setModerator(boolean moderator) {
        isModerator = moderator;
    }

    public Timestamp getRegistrationTime() {
        return registrationTime;
    }

    public void setRegistrationTime(Timestamp registrationTime) {
        this.registrationTime = registrationTime;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getHashedPassword() {
        return hashedPassword;
    }

    public void setHashedPassword(String hashedPassword) {
        this.hashedPassword = hashedPassword;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getPhoto() {
        return photo;
    }

    public void setPhoto(String photo) {
        this.photo = photo;
    }

    public Set<Post> getPosts() {
        return posts;
    }

    public void setPosts(Set<Post> posts) {
        this.posts = posts;
    }

    public Set<PostVote> getPostVotes() {
        return postVotes;
    }

    public void setPostVotes(Set<PostVote> postVotes) {
        this.postVotes = postVotes;
    }

    public Set<PostComment> getPostComments() {
        return postComments;
    }

    public void setPostComments(Set<PostComment> postComments) {
        this.postComments = postComments;
    }

    public Set<Post> getPostsModerated() {
        return postsModerated;
    }

    public void setPostsModerated(Set<Post> postsModerated) {
        this.postsModerated = postsModerated;
    }
}
