/*
 * jaoLicense
 *
 * Copyright (c) 2021 jao Minecraft Server
 *
 * The following license applies to this project: jaoLicense
 *
 * Japanese: https://github.com/jaoafa/jao-Minecraft-Server/blob/master/jaoLICENSE.md
 * English: https://github.com/jaoafa/jao-Minecraft-Server/blob/master/jaoLICENSE-en.md
 */

package com.jaoafa.javajaotan2.tasks;

import com.jaoafa.javajaotan2.Main;
import com.jaoafa.javajaotan2.lib.Channels;
import com.jaoafa.javajaotan2.lib.JavajaotanData;
import com.jaoafa.javajaotan2.lib.MySQLDBManager;
import com.jaoafa.javajaotan2.lib.SubAccount;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Minecraft-Discord Connect(/link) に基づき、以下の利用者整理処理を行う
 * ここでの1ヶ月は30日とする。
 * (dryRun引数がTrueの場合は対象のメンバーのみを抽出)
 * <p>
 * - 参加してから1週間以内にlink・サブアカウント登録・サポートへの問い合わせがない場合はキックする
 * - 参加してから3週間後にサポート問い合わせのみの場合はキックする
 * - linkされているのに、Guildにいない利用者のlinkを解除する
 * - 最終ログインから2ヶ月が経過している場合、警告リプライを#generalで送信する
 * - 最終ログインから3ヶ月が経過している場合、linkをdisabledにし、MinecraftConnected権限を剥奪する
 * - メインアカウントが1か月以上前に退出しているのにも関わらず、SubAccount役職がついている利用者から本役職を外す。
 * <p>
 * - 解除関連処理時、ExpiredDateが設定されている場合は、期限日は最終ログインから3ヶ月後かExpiredDateのいずれか遅い方を使用する
 * <p>
 */
public class Task_MemberOrganize implements Job {
    boolean dryRun;
    Logger logger;
    Connection conn;

    TextChannel Channel_General;

    public Task_MemberOrganize() {
        this.dryRun = true; // 数日間動作確認
    }

    public Task_MemberOrganize(boolean dryRun) {
        this.dryRun = dryRun;
    }

    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        logger = Main.getLogger();
        Guild guild = Main.getJDA().getGuildById(Main.getConfig().getGuildId());

        if (guild == null) {
            logger.warn("guild == null");
            return;
        }

        MySQLDBManager manager = JavajaotanData.getMainMySQLDBManager();
        try {
            conn = manager.getConnection();
        } catch (SQLException e) {
            e.printStackTrace();
            return;
        }

        List<RunMemberOrganize.MinecraftDiscordConnection> connections = getConnections();

        if (connections == null) {
            logger.warn("connections == null");
            return;
        }

        Roles.setGuildAndRole(guild);

        Channel_General = Channels.general.getChannel();

        if (Channel_General == null) {
            logger.warn("Channel_General == null");
            return;
        }

        ExecutorService service = Executors.newFixedThreadPool(10);
        guild.loadMembers()
            .onSuccess(members ->
                {
                    logger.info("loadMember(): %s".formatted(members.size()));
                    members.forEach(member ->
                        service.execute(new RunMemberOrganize(connections, member.hasTimeJoined() ? member : guild.retrieveMember(member.getUser()).complete()))
                    );
                }
            );
    }

    private List<RunMemberOrganize.MinecraftDiscordConnection> getConnections() {
        List<RunMemberOrganize.MinecraftDiscordConnection> connections = new ArrayList<>();
        List<UUID> inserted = new ArrayList<>();
        try {
            try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM discordlink ORDER BY id DESC")) {
                try (ResultSet res = stmt.executeQuery()) {
                    while (res.next()) {
                        if (inserted.contains(UUID.fromString(res.getString("uuid")))) continue;
                        connections.add(new RunMemberOrganize.MinecraftDiscordConnection(
                            res.getString("player"),
                            UUID.fromString(res.getString("uuid")),
                            res.getString("disid"),
                            res.getString("discriminator"),
                            getLeastLogin(UUID.fromString(res.getString("uuid"))),
                            res.getTimestamp("expired_date"),
                            res.getTimestamp("dead_at"),
                            res.getBoolean("disabled")
                        ));
                        inserted.add(UUID.fromString(res.getString("uuid")));
                    }
                }
            }
            return connections;
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    private Timestamp getLeastLogin(UUID uuid) {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM login WHERE uuid = ? AND login_success = ? ORDER BY id DESC LIMIT 1")) {

            stmt.setString(1, uuid.toString());
            stmt.setBoolean(2, true);

            ResultSet result = stmt.executeQuery();

            if (!result.next()) return null;
            else return result.getTimestamp("date");
        } catch (SQLException e) {
            return null;
        }
    }

    class RunMemberOrganize implements Runnable {
        List<MinecraftDiscordConnection> connections;
        Member member;

        public RunMemberOrganize(List<MinecraftDiscordConnection> connections, Member member) {
            this.connections = connections;
            this.member = member;
        }

        @Override
        public void run() {
            Guild guild = member.getGuild();
            if (member.getUser().isBot()) {
                logger.info("[%s] Bot".formatted(
                    member.getUser().getAsTag()
                ));
                return;
            }

            logger.info("[%s] Role: %s".formatted(
                member.getUser().getAsTag(),
                member.getRoles().stream().map(Role::getName).collect(Collectors.joining(", "))
            ));
            Notified notified = new Notified(member);
            MinecraftDiscordConnection mdc = connections
                .stream()
                .filter(c -> c.disid().equals(member.getId()))
                .findFirst()
                .orElse(null);

            boolean isMinecraftConnected = isGrantedRole(member, Roles.MinecraftConnected.role);
            boolean isNeedSupport = isGrantedRole(member, Roles.NeedSupport.role);
            boolean isSubAccount = isGrantedRole(member, Roles.SubAccount.role);

            String minecraftId;
            UUID uuid = null;
            if (mdc != null) {
                minecraftId = mdc.player();
                uuid = mdc.uuid();
                logger.info("[%s] Minecraft link: %s (%s)".formatted(
                    member.getUser().getAsTag(),
                    minecraftId,
                    uuid
                ));
            } else {
                logger.info("[%s] Minecraft link: NOT LINKED".formatted(
                    member.getUser().getAsTag()
                ));
            }

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime joinedTime = member.getTimeJoined().atZoneSameInstant(ZoneId.of("Asia/Tokyo")).toLocalDateTime();
            LocalDateTime loginDate = mdc != null ? mdc.loginDate().toLocalDateTime() : null;
            long joinDays = ChronoUnit.DAYS.between(joinedTime, now);
            logger.info("[%s] joinDays: %s days".formatted(member.getUser().getAsTag(), joinDays));

            boolean doReturn = false;
            BiFunction<String, String, Boolean> doKick = (title, description) -> {
                kickDiscord(member, title, description);
                return true;
            };

            if (!isMinecraftConnected && !isSubAccount && !isNeedSupport && mdc == null && joinDays >= 7) {
                // 参加してから1週間以内にlink・サブアカウント登録・サポートへの問い合わせがない場合はキックする
                doReturn = doKick.apply("1weekキック", "1週間(7日)以上link・サブアカウント登録・サポートへの問い合わせがなかったため、キックしました。");
            } else if (!isMinecraftConnected && !isSubAccount && isNeedSupport && mdc == null && joinDays >= 21) {
                // 参加してから3週間後にサポート問い合わせのみの場合はキックする
                doReturn = doKick.apply("3weeksキック (NeedSupport)", "3週間(21日)以上link・サブアカウント登録がなかったため、キックしました。");
            }

            if (doReturn) return;

            SubAccount subAccount = new SubAccount(member);

            if (isSubAccount && !subAccount.isSubAccount()) {
                // SubAccount役職なのにサブアカウントではない
                notifyConnection(member, "SubAccount役職剥奪", "SubAccount役職が付与されていましたが、サブアカウント登録がなされていないため剥奪しました。", Color.RED, mdc);
                if (!dryRun) guild.addRoleToMember(member, Roles.SubAccount.role).queue();
                isSubAccount = false;
            }

            if (isSubAccount) {
                // メインアカウントが1か月以上前に退出しているのにも関わらず、SubAccount役職がついている利用者から本役職を外す。
                // 多分この実装は不十分
                MinecraftDiscordConnection subMdc = connections
                    .stream()
                    .filter(c -> c.disid().equals(subAccount.getMainAccount().getUser().getId()))
                    .findFirst()
                    .orElse(null);

                //description,isSubAccount
                Function<String, Boolean> doSubAccountRemove = description -> {
                    notifyConnection(member, "SubAccount役職剥奪", description, Color.YELLOW, mdc);
                    if (!dryRun) {
                        subAccount.removeMainAccount();
                        guild.addRoleToMember(member, Roles.SubAccount.role).queue();
                    }
                    return false;
                };

                if (subMdc == null) {
                    //取得失敗
                    isSubAccount = doSubAccountRemove.apply("メインアカウントの情報が取得できなかったため、剥奪しました。");
                } else if (subMdc.disabled()) {
                    long deadDays = ChronoUnit.DAYS.between(subMdc.dead_at().toLocalDateTime(), now);
                    if (deadDays >= 30) {
                        // disabled & 30日経過
                        isSubAccount = doSubAccountRemove.apply("メインアカウントが1か月(30日)以上前にlinkを切断しているため、剥奪しました。");
                    }
                }
            }

            // サブアカウントの場合、10分チェックとかのみ (Minecraft linkされている場合は除外)
            if (isSubAccount && !isMinecraftConnected) return;

            if (isMinecraftConnected && uuid != null) {
                Timestamp checkTS = getMaxTimestamp(mdc.loginDate(), mdc.expired_date());
                long checkDays = loginDate != null ? ChronoUnit.DAYS.between(checkTS.toLocalDateTime(), now) : -1;
                logger.info("[%s] checkDays: %s".formatted(member.getUser().getAsTag(), checkDays));
                // 最終ログインから2ヶ月(60日)が経過している場合、警告リプライを#generalで送信する
                if (checkDays >= 60 && !notified.isNotified(Notified.NotifiedType.MONTH2)) {
                    notifyConnection(member, "2か月経過", "最終ログインから2か月が経過したため、#generalで通知します。", Color.MAGENTA, mdc);
                    if (!dryRun) {
                        Channel_General.sendMessage("""
                            <#%s> あなたのDiscordアカウントに接続されているMinecraftアカウント「`%s`」が**最終ログインから2ヶ月経過**致しました。
                            **サーバルール及び個別規約により、3ヶ月を経過すると建築物や自治体の所有権がなくなり、運営によって撤去・移動ができる**ようになり、またMinecraftアカウントとの連携が自動的に解除されます。
                            本日から1ヶ月以内にjao Minecraft Serverにログインがなされない場合、上記のような対応がなされる場合がございますのでご注意ください。""").queue();
                    }
                    notified.setNotified(Notified.NotifiedType.MONTH2);
                }

                // 最終ログインから3ヶ月が経過している場合、linkをdisabledにし、MinecraftConnected権限を剥奪する
                if (checkDays >= 90) {
                    notifyConnection(member, "3monthリンク切断", "最終ログインから3か月が経過したため、linkを切断し、役職を剥奪します。", Color.ORANGE, mdc);
                    if (!dryRun) {
                        disableLink(mdc, uuid);
                        guild.removeRoleFromMember(member, Roles.MinecraftConnected.role).queue();
                    }
                    isMinecraftConnected = false;
                }
                if (isMinecraftConnected && isSubAccount) {
                    if (!dryRun) {
                        guild.removeRoleFromMember(member, Roles.SubAccount.role).queue();
                    }
                }
            }
        }

        Timestamp getMaxTimestamp(Timestamp a, Timestamp b) {
            if (a == null && b != null) return b;
            if (a != null && b == null) return a;
            if (a == null) return null;
            if (a.before(b)) {
                return b;
            } else if (b.before(a)) {
                return a;
            } else if (a.equals(b)) {
                return a;
            }
            return null;
        }


        private boolean isGrantedRole(Member member, Role role) {
            boolean isGranted = member
                .getRoles()
                .stream()
                .map(ISnowflake::getIdLong)
                .anyMatch(i -> role.getIdLong() == i);
            if (isGranted) {
                logger.info("[%s] %s".formatted(
                    member.getUser().getAsTag(),
                    "is%s: true".formatted(role.getName())
                ));
            }
            return isGranted;
        }

        private void notifyConnection(Member member, String title, String description, Color color, MinecraftDiscordConnection mdc) {
            TextChannel channel = Main.getJDA().getTextChannelById(891021520099500082L);

            if (channel == null) return;

            EmbedBuilder embed = new EmbedBuilder()
                .setTitle(title)
                .setDescription(description)
                .setColor(color)
                .setAuthor(member.getUser().getAsTag(), "https://discord.com/users/" + member.getId(), member.getUser().getEffectiveAvatarUrl());
            if (mdc != null) {
                embed.addField("MinecraftDiscordConnection", "[%s](https://users.jaoafa.com/%s)".formatted(mdc.player(), mdc.uuid().toString()), false);
            }
            if (dryRun) embed.setFooter("DRY-RUN MODE");

            channel.sendMessageEmbeds(embed.build()).queue();
        }

        private void kickDiscord(Member member, String title, String description) {
            Path path = Path.of("pre-permsync.json");
            JSONObject object = new JSONObject();
            if (Files.exists(path)) {
                try {
                    object = new JSONObject(Files.readString(path));
                } catch (IOException e) {
                    logger.warn("grantDiscordPerm json load failed.", e);
                    return;
                }
            }

            if (!object.has("kick")) object.put("kick", new JSONArray());

            JSONArray kicks = object.getJSONArray("kick");

            if (kicks.toList().contains(member.getId()) && !dryRun) {
                // 処理
                notifyConnection(member, "[PROCESS] " + title, description, Color.PINK, null);
                member.getGuild().kick(member).queue();
                kicks.remove(kicks.toList().indexOf(member.getId()));
            } else {
                // 動作予告
                notifyConnection(member, "[PRE] " + title, "次回処理時、本動作が実施されます。", Color.PINK, null);
                kicks.put(member.getId());
            }

            object.put("kick", kicks);
            try {
                Files.writeString(path, object.toString());
            } catch (IOException e) {
                logger.warn("grantDiscordPerm json save failed.", e);
            }
        }

        private void disableLink(MinecraftDiscordConnection mdc, UUID uuid) {
            try (PreparedStatement stmt = conn.prepareStatement("UPDATE discordlink SET disabled = ? WHERE uuid = ? AND disabled = ?")) {
                stmt.setBoolean(1, true);
                stmt.setString(2, uuid.toString());
                stmt.setBoolean(3, true);
                stmt.execute();
            } catch (SQLException e) {
                logger.warn("disableLink(%s): failed".formatted(mdc.player + "#" + mdc.uuid), e);
            }
        }

        record MinecraftDiscordConnection(String player, UUID uuid, String disid, String discriminator,
                                          Timestamp loginDate,
                                          Timestamp expired_date, Timestamp dead_at, boolean disabled) {

        }

        class Notified {
            Path path = Path.of("permsync-notified.json");
            Member member;
            String memberId;

            Notified(Member member) {
                this.member = member;
                this.memberId = member.getId();
            }

            private boolean isNotified(NotifiedType type) {
                JSONObject object = load();
                return object.has(memberId) && object.getJSONObject(memberId).has(type.name());
            }

            private void setNotified(NotifiedType type) {
                JSONObject object = load();
                JSONArray userObject = object.has(memberId) ? object.getJSONArray(memberId) : new JSONArray();
                userObject.put(type.name());
                object.put(memberId, userObject);
                try {
                    Files.writeString(path, object.toString());
                } catch (IOException e) {
                    logger.warn("Notified.setNotified is failed.", e);
                }
            }

            private JSONObject load() {
                JSONObject object = new JSONObject();
                if (Files.exists(path)) {
                    try {
                        object = new JSONObject(Files.readString(path));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                return object;
            }

            enum NotifiedType {
                MONTH2
            }
        }
    }

    public enum Roles {
        Regular(597405176189419554L, null),
        CommunityRegular(888150763421970492L, null),
        Verified(597405176969560064L, null),
        MinecraftConnected(604011598952136853L, null),
        MailVerified(597421078817669121L, null),
        NeedSupport(786110419470254102L, null),
        SubAccount(753047225751568474L, null),
        Unknown(null, null);

        private final Long id;
        private Role role;

        Roles(Long id, Role role) {
            this.id = id;
            this.role = role;
        }

        public void setRole(Role role) {
            this.role = role;
        }

        /**
         * Guildからロールを取得します
         *
         * @param guild 対象Guild
         */
        public static void setGuildAndRole(Guild guild) {
            for (Roles role : Roles.values()) {
                if (role.equals(Unknown)) continue;
                role.setRole(guild.getRoleById(role.id));
            }
        }

        /**
         * 名前からロールを取得します
         *
         * @param name ロールの名前
         *
         * @return 取得したロール
         */
        public static Roles get(String name) {
            return Arrays.stream(values())
                .filter(role -> role.name().equalsIgnoreCase(name))
                .findFirst()
                .orElse(Roles.Unknown);
        }
    }
}