package cn.android666.im.server.org;

import cn.android666.im.server.org.db.OrgDao;
import cn.android666.im.server.org.model.Department;
import cn.android666.im.server.org.model.Employee;
import cn.android666.im.server.org.model.OrgChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 组织架构的业务逻辑：内存中保存全部部门和员工（1 万人约几 MB），查询直接读内存；
 * 写操作先写数据库（与变更日志同一事务），成功后再更新内存。
 *
 * 只考虑单机部署（Openfire 不做集群）。写操作串行执行（synchronized），读操作无锁。
 *
 * 与 Openfire 无关，单元测试用 H2 内存数据库驱动。
 */
public class OrgService {

    private static final Logger Log = LoggerFactory.getLogger(OrgService.class);

    /** 变更日志保留时间：30 天（《三端协议规范.md》§4.4） */
    public static final long CHANGE_RETENTION_MS = 30L * 24 * 60 * 60 * 1000;

    /** 部门排序：sort 升序，其次名称、ID */
    private static final Comparator<Department> DEPARTMENT_ORDER = Comparator.comparingInt(Department::sort)
            .thenComparing(Department::name).thenComparing(Department::id);

    /** 员工排序：sort 升序，其次拼音、工号 */
    private static final Comparator<Employee> EMPLOYEE_ORDER = Comparator.comparingInt(Employee::sort)
            .thenComparing(Employee::pinyin).thenComparing(Employee::username);

    /** 数据库访问 */
    private final OrgDao dao;

    /** 当前时间（毫秒），测试中可替换 */
    private final LongSupplier clock;

    /** 全部部门，键为部门 ID */
    private final Map<String, Department> departments = new ConcurrentHashMap<>();

    /** 全部员工（含已禁用的），键为工号 */
    private final Map<String, Employee> employees = new ConcurrentHashMap<>();

    /** 各部门的直属在职人数缓存；写操作后置为 null，下次读取时重新统计 */
    private volatile Map<String, Integer> memberCounts;

    /** 当前组织架构版本号 */
    private volatile long version;

    /** 已清理的变更日志的最大版本号 */
    private volatile long prunedVersion;

    /**
     * 创建服务。创建后需调用 {@link #load()} 从数据库加载数据。
     *
     * @param dao   数据库访问
     * @param clock 当前时间（毫秒）
     */
    public OrgService(OrgDao dao, LongSupplier clock) {
        this.dao = dao;
        this.clock = clock;
    }

    /**
     * 从数据库加载全部部门和员工。
     *
     * @throws SQLException 数据库错误
     */
    public synchronized void load() throws SQLException {
        departments.clear();
        employees.clear();
        for (Department d : dao.loadDepartments()) {
            departments.put(d.id(), d);
        }
        for (Employee e : dao.loadEmployees()) {
            employees.put(e.username(), e);
        }
        version = dao.currentVersion();
        prunedVersion = dao.prunedVersion();
        memberCounts = null;
        Log.info("组织架构已加载：部门 {} 个，员工 {} 人，版本 {}", departments.size(), employees.size(), version);
    }

    // ---------------------------------------------------------------- 查询

    /**
     * 当前组织架构版本号。
     *
     * @return 版本号
     */
    public long version() {
        return version;
    }

    /**
     * 按工号查询员工（含已禁用的）。
     *
     * @param username 工号
     * @return 员工；不在组织架构中时为空
     */
    public Optional<Employee> employee(String username) {
        return Optional.ofNullable(employees.get(username));
    }

    /**
     * 按 ID 查询部门。
     *
     * @param id 部门 ID
     * @return 部门；不存在时为空
     */
    public Optional<Department> department(String id) {
        return Optional.ofNullable(departments.get(id));
    }

    /**
     * 全部部门（管理后台用）。
     *
     * @return 部门列表，按上级部门、排序号排列
     */
    public List<Department> allDepartments() {
        List<Department> list = new ArrayList<>(departments.values());
        list.sort(Comparator.comparing(Department::parentId).thenComparing(DEPARTMENT_ORDER));
        return list;
    }

    /**
     * 全部员工（含已禁用的，管理后台用）。
     *
     * @return 员工列表，按工号排列
     */
    public List<Employee> allEmployees() {
        List<Employee> list = new ArrayList<>(employees.values());
        list.sort(Comparator.comparing(Employee::username));
        return list;
    }

    /**
     * 账号是否需要强制修改初始密码。
     *
     * @param username 工号
     * @return 需要时为 true；不在组织架构中的账号（如 admin）为 false
     */
    public boolean mustChangePassword(String username) {
        Employee e = employees.get(username);
        return e != null && e.mustChangePassword();
    }

    /**
     * 部门的直属在职人数（不含子部门）。
     *
     * @param deptId 部门 ID
     * @return 人数
     */
    public int memberCount(String deptId) {
        Map<String, Integer> counts = memberCounts;
        if (counts == null) {
            counts = new HashMap<>();
            for (Employee e : employees.values()) {
                if (!e.disabled()) {
                    counts.merge(e.deptId(), 1, Integer::sum);
                }
            }
            memberCounts = counts;
        }
        return counts.getOrDefault(deptId, 0);
    }

    /**
     * 部门下级（《三端协议规范.md》§4.2）的查询结果。
     *
     * @param departments 下级部门（全部，不分页）
     * @param employees   本页的直属员工
     * @param firstIndex  本页第一个员工在全部直属员工中的下标
     * @param total       直属员工总数
     */
    public record Children(List<Department> departments, List<Employee> employees, int firstIndex, int total) {
    }

    /**
     * 查询部门的下级部门和直属员工。
     *
     * @param deptId     部门 ID，空字符串表示查询顶级部门
     * @param afterIndex 上一页最后一个员工的下标（RSM after），第一页传 -1
     * @param max        本页最多返回的员工数
     * @return 查询结果
     */
    public Children children(String deptId, int afterIndex, int max) {
        List<Department> subDepartments = departments.values().stream()
                .filter(d -> d.parentId().equals(deptId))
                .sorted(DEPARTMENT_ORDER)
                .toList();
        List<Employee> members = employees.values().stream()
                .filter(e -> !e.disabled() && e.deptId().equals(deptId))
                .sorted(EMPLOYEE_ORDER)
                .toList();
        int from = Math.max(0, afterIndex + 1);
        int to = Math.min(members.size(), from + max);
        List<Employee> page = from < to ? members.subList(from, to) : List.of();
        return new Children(subDepartments, page, from, members.size());
    }

    /**
     * 批量查询员工（§4.3）：返回的列表与参数一一对应，不存在或已禁用的员工为 null（应答为 op='delete'）。
     *
     * @param usernames 工号列表
     * @return 员工列表，与参数顺序一致
     */
    public List<Employee> employees(List<String> usernames) {
        List<Employee> result = new ArrayList<>(usernames.size());
        for (String username : usernames) {
            Employee e = username == null ? null : employees.get(username);
            result.add(e == null || e.disabled() ? null : e);
        }
        return result;
    }

    // ---------------------------------------------------------------- 同步

    /**
     * 同步结果中的一项：部门或员工，upsert 或 delete。
     *
     * @param key        分页游标（{@code d:部门ID} / {@code e:工号}），按它排序
     * @param department 部门（upsert 部门时非空）
     * @param employee   员工（upsert 员工时非空）
     * @param deletedId  被删除的部门 ID 或工号（delete 时非空）
     */
    public record SyncItem(String key, Department department, Employee employee, String deletedId) {

        /**
         * 是否为删除。
         *
         * @return delete 时为 true
         */
        public boolean isDelete() {
            return deletedId != null;
        }

        /**
         * 是否为部门。
         *
         * @return 部门时为 true
         */
        public boolean isDepartment() {
            return key.startsWith(KEY_DEPARTMENT);
        }
    }

    /** 部门游标前缀（排在员工前面，客户端先写入部门） */
    static final String KEY_DEPARTMENT = "d:";

    /** 员工游标前缀 */
    static final String KEY_EMPLOYEE = "e:";

    /**
     * 同步结果的一页（§4.4）。
     *
     * @param version 本次同步完成后客户端应保存的版本号
     * @param full    是否为全量数据（客户端需先清空本地组织架构）
     * @param items   本页的变更项
     * @param total   全部变更项的数量
     */
    public record SyncPage(long version, boolean full, List<SyncItem> items, int total) {
    }

    /**
     * 组织架构同步：客户端版本号为 0、过旧（变更日志已清理）或比服务器新（服务器数据被重建）时返回全量，否则返回增量。
     *
     * @param clientVersion 客户端已同步到的版本号
     * @param afterKey      上一页最后一项的游标（RSM after），第一页传 null
     * @param max           本页最多返回的条数
     * @return 本页结果
     * @throws SQLException 读取变更日志失败
     */
    public SyncPage sync(long clientVersion, String afterKey, int max) throws SQLException {
        long current = version;
        boolean full = clientVersion <= 0 || clientVersion > current || clientVersion < prunedVersion;
        List<SyncItem> items = full ? fullItems() : incrementalItems(clientVersion);
        items.sort(Comparator.comparing(SyncItem::key));
        int from = 0;
        if (afterKey != null) {
            while (from < items.size() && items.get(from).key().compareTo(afterKey) <= 0) {
                from++;
            }
        }
        int to = Math.min(items.size(), from + max);
        List<SyncItem> page = from < to ? new ArrayList<>(items.subList(from, to)) : List.of();
        return new SyncPage(current, full, page, items.size());
    }

    /**
     * 全量数据：全部部门和在职员工。
     *
     * @return 变更项
     */
    private List<SyncItem> fullItems() {
        List<SyncItem> items = new ArrayList<>(departments.size() + employees.size());
        for (Department d : departments.values()) {
            items.add(new SyncItem(KEY_DEPARTMENT + d.id(), d, null, null));
        }
        for (Employee e : employees.values()) {
            if (!e.disabled()) {
                items.add(new SyncItem(KEY_EMPLOYEE + e.username(), null, e, null));
            }
        }
        return items;
    }

    /**
     * 增量数据：客户端版本之后变更过的部门和员工的当前状态（存在则 upsert，已删除 / 已禁用则 delete）。
     *
     * @param clientVersion 客户端版本号
     * @return 变更项
     * @throws SQLException 读取变更日志失败
     */
    private List<SyncItem> incrementalItems(long clientVersion) throws SQLException {
        Set<String> keys = new LinkedHashSet<>();
        for (OrgChange change : dao.changesSince(clientVersion)) {
            keys.add((change.entityType() == OrgChange.TYPE_DEPARTMENT ? KEY_DEPARTMENT : KEY_EMPLOYEE) + change.entityId());
        }
        List<SyncItem> items = new ArrayList<>(keys.size());
        for (String key : keys) {
            String id = key.substring(2);
            if (key.startsWith(KEY_DEPARTMENT)) {
                Department d = departments.get(id);
                items.add(d != null ? new SyncItem(key, d, null, null) : new SyncItem(key, null, null, id));
            } else {
                Employee e = employees.get(id);
                items.add(e != null && !e.disabled() ? new SyncItem(key, null, e, null) : new SyncItem(key, null, null, id));
            }
        }
        return items;
    }

    // ---------------------------------------------------------------- 搜索

    /**
     * 搜索同事（§4.5）：匹配姓名、全拼、首字母、工号、手机、部门路径（任一级部门名称），按匹配度排序。
     *
     * @param keyword 关键字
     * @param max     最多返回的条数
     * @return 在职员工列表，匹配度高的在前
     */
    public List<Employee> search(String keyword, int max) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (kw.isEmpty()) {
            return List.of();
        }
        record Scored(Employee employee, int score) {
        }
        List<Scored> matches = new ArrayList<>();
        // 同一部门的路径只算一次
        Map<String, String> paths = new HashMap<>();
        for (Employee e : employees.values()) {
            if (e.disabled()) {
                continue;
            }
            String deptPath = paths.computeIfAbsent(e.deptId(), id -> departments.containsKey(id) ? path(id) : "");
            int score = SearchScorer.score(kw, e, deptPath);
            if (score > 0) {
                matches.add(new Scored(e, score));
            }
        }
        matches.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(s -> s.employee().pinyin())
                .thenComparing(s -> s.employee().username()));
        return matches.stream().limit(max).map(Scored::employee).toList();
    }

    // ---------------------------------------------------------------- 写操作

    /**
     * 新增部门。
     *
     * @param parentId 上级部门 ID，空字符串表示顶级部门
     * @param name     部门名称
     * @param sort     同级排序
     * @return 新部门
     * @throws SQLException             数据库错误
     * @throws IllegalArgumentException 上级部门不存在或名称为空
     */
    public synchronized Department createDepartment(String parentId, String name, int sort) throws SQLException {
        requireParent(parentId);
        String trimmed = requireName(name);
        Department created = dao.inTransaction(c -> {
            Department d = new Department(dao.nextDepartmentId(c), parentId, trimmed, sort);
            dao.saveDepartment(c, d);
            recordChange(c, OrgChange.TYPE_DEPARTMENT, d.id());
            return d;
        });
        departments.put(created.id(), created);
        Log.info("新增部门：{}", created);
        return created;
    }

    /**
     * 修改部门（名称、上级、排序）。
     *
     * @param department 修改后的部门
     * @throws SQLException             数据库错误
     * @throws IllegalArgumentException 部门不存在、上级部门不存在或会形成循环
     */
    public synchronized void updateDepartment(Department department) throws SQLException {
        if (!departments.containsKey(department.id())) {
            throw new IllegalArgumentException("部门不存在：" + department.id());
        }
        requireParent(department.parentId());
        // 不能把部门移到自己或自己的下级部门下面
        for (String p = department.parentId(); !p.isEmpty(); p = departments.get(p).parentId()) {
            if (p.equals(department.id())) {
                throw new IllegalArgumentException("不能把部门移到自己或下级部门下面");
            }
        }
        Department updated = new Department(department.id(), department.parentId(), requireName(department.name()),
                department.sort());
        dao.inTransaction(c -> {
            dao.saveDepartment(c, updated);
            recordChange(c, OrgChange.TYPE_DEPARTMENT, updated.id());
            return null;
        });
        departments.put(updated.id(), updated);
        Log.info("修改部门：{}", updated);
    }

    /**
     * 删除部门：只能删除没有下级部门、没有员工（含已禁用的）的部门。
     *
     * @param id 部门 ID
     * @throws SQLException             数据库错误
     * @throws IllegalArgumentException 部门不存在或不为空
     */
    public synchronized void deleteDepartment(String id) throws SQLException {
        if (!departments.containsKey(id)) {
            throw new IllegalArgumentException("部门不存在：" + id);
        }
        boolean hasChildren = departments.values().stream().anyMatch(d -> d.parentId().equals(id));
        boolean hasMembers = employees.values().stream().anyMatch(e -> e.deptId().equals(id));
        if (hasChildren || hasMembers) {
            throw new IllegalArgumentException("部门下还有下级部门或员工，不能删除");
        }
        dao.inTransaction(c -> {
            dao.deleteDepartment(c, id);
            recordChange(c, OrgChange.TYPE_DEPARTMENT, id);
            return null;
        });
        departments.remove(id);
        Log.info("删除部门：{}", id);
    }

    /**
     * 按部门路径查找部门，不存在的部门逐级创建（Excel 导入用，如 {@code 总部/研发中心/Android组}）。
     *
     * @param names 从顶级到末级的部门名称
     * @return 末级部门 ID
     * @throws SQLException             数据库错误
     * @throws IllegalArgumentException 路径为空
     */
    public synchronized String ensureDepartmentPath(List<String> names) throws SQLException {
        if (names.isEmpty()) {
            throw new IllegalArgumentException("部门路径为空");
        }
        String parentId = Department.ROOT;
        for (String raw : names) {
            String name = requireName(raw);
            String finalParentId = parentId;
            Optional<Department> existing = departments.values().stream()
                    .filter(d -> d.parentId().equals(finalParentId) && d.name().equals(name))
                    .min(Comparator.comparing(Department::id));
            if (existing.isPresent()) {
                parentId = existing.get().id();
            } else {
                // 新部门排在同级末尾
                int sort = departments.values().stream().filter(d -> d.parentId().equals(finalParentId))
                        .mapToInt(Department::sort).max().orElse(0) + 10;
                parentId = createDepartment(finalParentId, name, sort).id();
            }
        }
        return parentId;
    }

    /**
     * 新增或更新员工。员工的部门变化、新增、禁用都会改变部门人数，所以同时记录相关部门的变更。
     *
     * @param employee 员工
     * @throws SQLException             数据库错误
     * @throws IllegalArgumentException 所属部门不存在
     */
    public synchronized void saveEmployee(Employee employee) throws SQLException {
        if (!departments.containsKey(employee.deptId())) {
            throw new IllegalArgumentException("部门不存在：" + employee.deptId());
        }
        Employee old = employees.get(employee.username());
        if (employee.equals(old)) {
            return;
        }
        // 只有通讯录可见的字段变化时才需要同步（强制改密标记客户端看不到）
        boolean visibleChange = old == null || !old.withMustChangePassword(false).equals(employee.withMustChangePassword(false));
        dao.inTransaction(c -> {
            dao.saveEmployee(c, employee);
            if (visibleChange) {
                recordChange(c, OrgChange.TYPE_EMPLOYEE, employee.username());
                // 部门人数变化：新增、调岗、禁用 / 启用
                if (old == null || !old.deptId().equals(employee.deptId()) || old.disabled() != employee.disabled()) {
                    recordChange(c, OrgChange.TYPE_DEPARTMENT, employee.deptId());
                    if (old != null && !old.deptId().equals(employee.deptId())) {
                        recordChange(c, OrgChange.TYPE_DEPARTMENT, old.deptId());
                    }
                }
            }
            return null;
        });
        employees.put(employee.username(), employee);
        memberCounts = null;
        Log.info("{}员工：{}（部门 {}，禁用 {}）", old == null ? "新增" : "更新", employee.username(),
                employee.deptId(), employee.disabled());
    }

    /**
     * 设置强制改密标记。
     *
     * @param username 工号
     * @param value    新的标记
     * @return 员工存在且标记有变化时为 true
     * @throws SQLException 数据库错误
     */
    public synchronized boolean setMustChangePassword(String username, boolean value) throws SQLException {
        Employee e = employees.get(username);
        if (e == null || e.mustChangePassword() == value) {
            return false;
        }
        saveEmployee(e.withMustChangePassword(value));
        return true;
    }

    /**
     * 禁用或启用员工。
     *
     * @param username 工号
     * @param disabled 是否禁用
     * @return 员工存在且状态有变化时为 true
     * @throws SQLException 数据库错误
     */
    public synchronized boolean setDisabled(String username, boolean disabled) throws SQLException {
        Employee e = employees.get(username);
        if (e == null || e.disabled() == disabled) {
            return false;
        }
        saveEmployee(e.withDisabled(disabled));
        return true;
    }

    /**
     * 更新头像哈希（vCard 头像变化时调用）。
     *
     * @param username   工号
     * @param avatarHash 头像 SHA-1，无头像时为空字符串
     * @throws SQLException 数据库错误
     */
    public synchronized void setAvatarHash(String username, String avatarHash) throws SQLException {
        Employee e = employees.get(username);
        if (e != null && !Objects.equals(e.avatarHash(), avatarHash)) {
            saveEmployee(e.withAvatarHash(avatarHash));
        }
    }

    /**
     * 清理 30 天前的变更日志。
     *
     * @return 删除的条数
     * @throws SQLException 数据库错误
     */
    public synchronized int pruneChanges() throws SQLException {
        int deleted = dao.pruneChanges(clock.getAsLong() - CHANGE_RETENTION_MS);
        prunedVersion = dao.prunedVersion();
        if (deleted > 0) {
            Log.info("清理变更日志 {} 条，已清理到版本 {}", deleted, prunedVersion);
        }
        return deleted;
    }

    /**
     * 记录一条变更并更新内存中的版本号（事务提交前更新也没关系：失败时版本号只会偏大，客户端会多拉一次）。
     *
     * @param connection 事务所在的连接
     * @param type       实体类型
     * @param id         部门 ID 或工号
     * @throws SQLException 数据库错误
     */
    private void recordChange(java.sql.Connection connection, char type, String id) throws SQLException {
        version = dao.recordChange(connection, type, id, clock.getAsLong());
    }

    /**
     * 检查上级部门存在。
     *
     * @param parentId 上级部门 ID，空字符串表示顶级
     */
    private void requireParent(String parentId) {
        if (!Department.ROOT.equals(parentId) && !departments.containsKey(parentId)) {
            throw new IllegalArgumentException("上级部门不存在：" + parentId);
        }
    }

    /**
     * 检查并整理部门名称。
     *
     * @param name 名称
     * @return 去掉首尾空白后的名称
     */
    private static String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("部门名称为空");
        }
        if (trimmed.contains("/")) {
            throw new IllegalArgumentException("部门名称不能包含 /");
        }
        return trimmed;
    }

    /**
     * 当前员工数量（含已禁用的），日志和管理后台用。
     *
     * @return 数量
     */
    public int employeeCount() {
        return employees.size();
    }

    /**
     * 当前部门数量。
     *
     * @return 数量
     */
    public int departmentCount() {
        return departments.size();
    }

    /**
     * 某部门及其全部下级部门的 ID（管理后台按部门筛选员工用）。
     *
     * @param deptId 部门 ID
     * @return 部门 ID 集合（含自身）
     */
    public Collection<String> subtree(String deptId) {
        Set<String> result = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>(List.of(deptId));
        while (!queue.isEmpty()) {
            String id = queue.remove(0);
            if (result.add(id)) {
                departments.values().stream().filter(d -> d.parentId().equals(id)).map(Department::id).forEach(queue::add);
            }
        }
        return result;
    }

    /**
     * 部门的完整路径（如 {@code 总部/研发中心/Android组}），管理后台显示用。
     *
     * @param deptId 部门 ID
     * @return 路径；部门不存在时返回 ID 本身
     */
    public String path(String deptId) {
        List<String> names = new ArrayList<>();
        String id = deptId;
        // 最多 50 级，防止数据异常时死循环
        for (int i = 0; i < 50 && id != null && !id.isEmpty(); i++) {
            Department d = departments.get(id);
            if (d == null) {
                break;
            }
            names.add(0, d.name());
            id = d.parentId();
        }
        return names.isEmpty() ? deptId : String.join("/", names);
    }
}
