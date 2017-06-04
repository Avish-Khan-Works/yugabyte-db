// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
#ifndef YB_CLIENT_YB_OP_H_
#define YB_CLIENT_YB_OP_H_

#include <memory>
#include <string>

#include "yb/common/partial_row.h"
#include "yb/common/partition.h"

#include "yb/client/meta_cache.h"

namespace yb {

class EncodedKey;

class RedisWriteRequestPB;
class RedisReadRequestPB;
class RedisResponsePB;

class YQLWriteRequestPB;
class YQLReadRequestPB;
class YQLResponsePB;

namespace client {

namespace internal {
class Batcher;
class AsyncRpc;
}  // namespace internal

class YBSession;
class YBStatusCallback;
class YBTable;

// A write or read operation operates on a single table and partial row.
// The YBOperation class itself allows the batcher to get to the
// generic information that it needs to process all write operations.
//
// On its own, the class does not represent any specific change and thus cannot
// be constructed independently.
//
// YBOperation also holds shared ownership of its YBTable to allow client's
// scope to end while the YBOperation is still alive.
class YBOperation {
 public:
  enum Type {
    INSERT = 1,
    UPDATE = 2,
    DELETE = 3,
    REDIS_WRITE = 4,
    REDIS_READ = 5,
    YQL_WRITE = 6,
    YQL_READ = 7,
  };
  virtual ~YBOperation();

  Status SetKey(const Slice& string_key);

  const YBTable* table() const { return table_.get(); }

  // See YBPartialRow API for field setters, etc.
  const YBPartialRow& row() const { return row_; }
  YBPartialRow* mutable_row() { return &row_; }

  virtual std::string ToString() const = 0;
  virtual Type type() const = 0;
  virtual bool read_only() = 0;

  virtual void SetHashCode(uint16_t hash_code) = 0;

  const scoped_refptr<internal::RemoteTablet>& tablet() const {
    return tablet_;
  }

  void SetTablet(const scoped_refptr<internal::RemoteTablet>& tablet) {
    tablet_ = tablet;
  }

  // Returns the partition key of the operation.
  virtual CHECKED_STATUS GetPartitionKey(std::string* partition_key) const;

 protected:
  explicit YBOperation(const std::shared_ptr<YBTable>& table);

  std::shared_ptr<YBTable> const table_;
  YBPartialRow row_;

 private:
  friend class internal::Batcher;
  friend class internal::AsyncRpc;

  // Return the number of bytes required to buffer this operation,
  // including direct and indirect data.
  int64_t SizeInBuffer() const;

  scoped_refptr<internal::RemoteTablet> tablet_;

  DISALLOW_COPY_AND_ASSIGN(YBOperation);
};

// A single row insert to be sent to the cluster.
// Row operation is defined by what's in the PartialRow instance here.
// Use mutable_row() to change the row being inserted
// An insert requires all key columns from the table schema to be defined.
class YBInsert : public YBOperation {
 public:
  virtual ~YBInsert();

  virtual std::string ToString() const override { return "INSERT " + row_.ToString(); }

  virtual bool read_only() override { return false; };

  // Note: SetHashCode only needed for Redis and YBQL operations. The empty method will be gone
  // when YBInsert / YBUpdate / YBDelete are deprecated.
  void SetHashCode(uint16_t hash_code) override {};

 protected:
  virtual Type type() const override {
    return INSERT;
  }

 private:
  friend class YBTable;
  explicit YBInsert(const std::shared_ptr<YBTable>& table);
};

// A single row update to be sent to the cluster.
// Row operation is defined by what's in the PartialRow instance here.
// Use mutable_row() to change the row being updated.
// An update requires the key columns and at least one other column
// in the schema to be defined.
class YBUpdate : public YBOperation {
 public:
  virtual ~YBUpdate();

  virtual std::string ToString() const override { return "UPDATE " + row_.ToString(); }

  virtual bool read_only() override { return false; };

  // Note: SetHashCode only needed for Redis and YBQL operations. The empty method will be gone
  // when YBInsert / YBUpdate / YBDelete are deprecated.
  void SetHashCode(uint16_t hash_code) override {};

 protected:
  virtual Type type() const override {
    return UPDATE;
  }

 private:
  friend class YBTable;
  explicit YBUpdate(const std::shared_ptr<YBTable>& table);
};


// A single row delete to be sent to the cluster.
// Row operation is defined by what's in the PartialRow instance here.
// Use mutable_row() to change the row being deleted
// A delete requires just the key columns to be defined.
class YBDelete : public YBOperation {
 public:
  virtual ~YBDelete();

  virtual std::string ToString() const override { return "DELETE " + row_.ToString(); }

  virtual bool read_only() override { return false; };

  // Note: SetHashCode only needed for Redis and YBQL operations. The empty method will be gone
  // when YBInsert / YBUpdate / YBDelete are deprecated.
  void SetHashCode(uint16_t hash_code) override {};

 protected:
  virtual Type type() const override {
    return DELETE;
  }

 private:
  friend class YBTable;
  explicit YBDelete(const std::shared_ptr<YBTable>& table);
};

class YBRedisWriteOp : public YBOperation {
 public:
  explicit YBRedisWriteOp(const std::shared_ptr<YBTable>& table);
  virtual ~YBRedisWriteOp();

  // Note: to avoid memory copy, this RedisWriteRequestPB is moved into tserver WriteRequestPB
  // when the request is sent to tserver. It is restored after response is received from tserver
  // (see WriteRpc's constructor).
  const RedisWriteRequestPB& request() const { return *redis_write_request_; }

  RedisWriteRequestPB* mutable_request() { return redis_write_request_.get(); }

  const RedisResponsePB& response() const { return *redis_response_; }

  RedisResponsePB* mutable_response();

  virtual std::string ToString() const override;

  virtual bool read_only() override { return false; };

  // Set the hash key in the WriteRequestPB.
  void SetHashCode(uint16_t hash_code) override;

 protected:
  virtual Type type() const override {
    return REDIS_WRITE;
  }

 private:
  friend class YBTable;
  std::unique_ptr<RedisWriteRequestPB> redis_write_request_;
  std::unique_ptr<RedisResponsePB> redis_response_;
};


class YBRedisReadOp : public YBOperation {
 public:
  explicit YBRedisReadOp(const std::shared_ptr<YBTable>& table);
  virtual ~YBRedisReadOp();

  // Note: to avoid memory copy, this RedisReadRequestPB is moved into tserver ReadRequestPB
  // when the request is sent to tserver. It is restored after response is received from tserver
  // (see ReadRpc's constructor).
  const RedisReadRequestPB& request() const { return *redis_read_request_; }

  RedisReadRequestPB* mutable_request() { return redis_read_request_.get(); }

  bool has_response() { return redis_response_ ? true : false; }

  const RedisResponsePB& response() const;

  RedisResponsePB* mutable_response();

  virtual std::string ToString() const override;

  virtual bool read_only() override { return true; };

  // Set the hash key in the ReadRequestPB.
  void SetHashCode(uint16_t hash_code) override;

 protected:
  virtual Type type() const override { return REDIS_READ; }

 private:
  friend class YBTable;
  std::unique_ptr<RedisReadRequestPB> redis_read_request_;
  std::unique_ptr<RedisResponsePB> redis_response_;
};


class YBqlOp : public YBOperation {
 public:
  virtual ~YBqlOp();

  const YQLResponsePB& response() const { return *yql_response_; }

  YQLResponsePB* mutable_response() { return yql_response_.get(); }

  std::string&& rows_data() { return std::move(rows_data_); }

  std::string* mutable_rows_data() { return &rows_data_; }

  // Set the row key in the YBPartialRow.
  virtual CHECKED_STATUS SetKey() = 0;

  // Set the hash key in the partial row of this YQL operation.
  virtual void SetHashCode(uint16_t hash_code) = 0;

 protected:
  explicit YBqlOp(const std::shared_ptr<YBTable>& table);
  std::unique_ptr<YQLResponsePB> yql_response_;
  std::string rows_data_;
};

class YBqlWriteOp : public YBqlOp {
 public:
  explicit YBqlWriteOp(const std::shared_ptr<YBTable>& table);
  virtual ~YBqlWriteOp();

  // Note: to avoid memory copy, this YQLWriteRequestPB is moved into tserver WriteRequestPB
  // when the request is sent to tserver. It is restored after response is received from tserver
  // (see WriteRpc's constructor).
  const YQLWriteRequestPB& request() const { return *yql_write_request_; }

  YQLWriteRequestPB* mutable_request() { return yql_write_request_.get(); }

  virtual std::string ToString() const override;

  virtual bool read_only() override { return false; };

  virtual CHECKED_STATUS SetKey() override;

  virtual void SetHashCode(uint16_t hash_code) override;

 protected:
  virtual Type type() const override {
    return YQL_WRITE;
  }

 private:
  friend class YBTable;
  static YBqlWriteOp *NewInsert(const std::shared_ptr<YBTable>& table);
  static YBqlWriteOp *NewUpdate(const std::shared_ptr<YBTable>& table);
  static YBqlWriteOp *NewDelete(const std::shared_ptr<YBTable>& table);
  std::unique_ptr<YQLWriteRequestPB> yql_write_request_;
};

class YBqlReadOp : public YBqlOp {
 public:
  virtual ~YBqlReadOp();

  static YBqlReadOp *NewSelect(const std::shared_ptr<YBTable>& table);

  // Note: to avoid memory copy, this YQLReadRequestPB is moved into tserver ReadRequestPB
  // when the request is sent to tserver. It is restored after response is received from tserver
  // (see ReadRpc's constructor).
  const YQLReadRequestPB& request() const { return *yql_read_request_; }

  YQLReadRequestPB* mutable_request() { return yql_read_request_.get(); }

  virtual std::string ToString() const override;

  virtual bool read_only() override { return true; };

  virtual CHECKED_STATUS SetKey() override;

  virtual void SetHashCode(uint16_t hash_code) override;

  // Returns the partition key of the read request if it exists.
  virtual CHECKED_STATUS GetPartitionKey(std::string* partition_key) const override;

  const YBConsistencyLevel yb_consistency_level() {
    return yb_consistency_level_;
  }

  void set_yb_consistency_level(const YBConsistencyLevel yb_consistency_level) {
    yb_consistency_level_ = yb_consistency_level;
  }

 protected:
  virtual Type type() const override { return YQL_READ; }

 private:
  friend class YBTable;
  explicit YBqlReadOp(const std::shared_ptr<YBTable>& table);
  std::unique_ptr<YQLReadRequestPB> yql_read_request_;
  YBConsistencyLevel yb_consistency_level_;
};


}  // namespace client
}  // namespace yb

#endif  // YB_CLIENT_YB_OP_H_
