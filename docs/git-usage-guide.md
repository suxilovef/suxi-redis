# Git 使用指南

## 1. 配置 Git 用户信息

```bash
# 查看当前仓库配置
git config user.name
git config user.email

# 查看全局配置
git config --global user.name
git config --global user.email

# 设置全局用户信息（邮箱建议用 GitHub 绑定邮箱）
git config --global user.name "你的名字"
git config --global user.email "你的邮箱"
```

## 2. 提交代码

```bash
# 暂存所有更改
git add .

# 提交
git commit -m "feat: init redis learn project"
```

## 3. 推送代码到 GitHub

### 3.1 先在 GitHub 创建仓库

打开 https://github.com/new，创建一个空仓库，**不要勾选** "Add a README file"。

### 3.2 添加远程仓库

```bash
# HTTP 方式
git remote add origin https://github.com/<你的用户名>/<仓库名>.git

# SSH 方式
git remote add origin git@github.com:<你的用户名>/<仓库名>.git
```

### 3.3 推送到 GitHub

```bash
git push -u origin master
```

## 4. 常见问题

### LF/CRLF 换行符警告

在 Windows 上 `git add` 时出现类似以下警告是正常现象，不影响代码功能，可以忽略：

```
warning: LF will be replaced by CRLF the next time Git touches it
```

原因：Windows 默认 `core.autocrlf=true`，Git 会在提交时把 CRLF 转为 LF 存入仓库，检出时再转回 CRLF，保证跨平台协作时仓库内统一使用 LF 换行符。
