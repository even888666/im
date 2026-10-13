# 开发环境测试数据

| 文件 | 用途 |
| --- | --- |
| `employees.csv` | 开发环境的测试员工（工号 10001 ~ 10020），`IM_ENV=dev` 时由 `scripts/import-testdata.sh` 导入 |

- `@TEST_USER_PASSWORD@` 在导入前替换为 `.env` 中的 `TEST_USER_PASSWORD`（默认 `Test1234`）。
- 10001 ~ 10010 的账号由 Openfire 安装时自动创建（见 `docker/openfire/entrypoint.sh`），导入时只补充组织架构信息、不改密码；
  10011 ~ 10020 由导入新建。开发环境的新账号不强制改密（`ORG_IMPORT_MUST_CHANGE_PASSWORD` 默认 false）。
- 姓名中特意包含多音字姓氏（曾、单）、复姓（欧阳）、ü（吕）、英文名、少数民族间隔号，用于验证拼音和搜索。
- 内容不变时不会重复导入；修改本文件后重新运行 `up.sh` 即可更新。
